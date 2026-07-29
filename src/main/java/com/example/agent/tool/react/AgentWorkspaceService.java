package com.example.agent.tool.react;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class AgentWorkspaceService {

    private static final int MAX_FILE_NAME_LENGTH = 240;
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final Path root;

    public AgentWorkspaceService(@Value("${agent.react.workspace-root:data/agent-workspace}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Path workspace(Long userId, Long sessionId) {
        Path workspace = workspacePath(userId, sessionId);
        try {
            Files.createDirectories(workspace);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create agent workspace: " + ex.getMessage(), ex);
        }
        return workspace;
    }

    /** Best-effort recursive removal of one session's workspace directory. */
    public void deleteWorkspace(Long userId, Long sessionId) {
        Path workspace = workspacePath(userId, sessionId);
        if (!workspace.startsWith(root) || !Files.exists(workspace)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    // A locked file must not block session deletion. The private
                    // root prevents leftovers from becoming directly accessible.
                    log.warn("Failed to delete Agent workspace path {}: {}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("Failed to traverse Agent workspace {} for deletion: {}", workspace, ex.getMessage());
        }
    }

    public Path resolveInside(Path workspace, String fileName) {
        String safeName = sanitizeFileName(fileName);
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        Path resolved = normalizedWorkspace.resolve(safeName).normalize();
        if (!resolved.startsWith(normalizedWorkspace)) {
            throw new IllegalArgumentException("File path escapes the agent workspace.");
        }
        return resolved;
    }

    /**
     * Allocate a unique storage directory while preserving the requested file
     * name. This prevents a later tool call from overwriting an earlier output.
     */
    public Path createArtifactPath(Path workspace, String fileName) {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        String safeName = sanitizeFileName(fileName);
        Path resolved = normalizedWorkspace
                .resolve("artifacts")
                .resolve(UUID.randomUUID().toString())
                .resolve(safeName)
                .normalize();
        if (!resolved.startsWith(normalizedWorkspace)) {
            throw new IllegalArgumentException("File path escapes the agent workspace.");
        }
        return resolved;
    }

    /**
     * Returns a stable artifact path for one logical tool operation. Repeating
     * the same operation therefore reuses the existing file instead of
     * allocating another UUID directory.
     */
    public Path createIdempotentArtifactPath(Path workspace, String fileName, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return createArtifactPath(workspace, fileName);
        }
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        String safeName = sanitizeFileName(fileName);
        String keyHash = sha256(idempotencyKey);
        Path resolved = normalizedWorkspace
                .resolve("artifacts")
                .resolve("idempotent")
                .resolve(keyHash)
                .resolve(safeName)
                .normalize();
        if (!resolved.startsWith(normalizedWorkspace)) {
            throw new IllegalArgumentException("File path escapes the agent workspace.");
        }
        return resolved;
    }

    /** Canonical file name used both for storage and duplicate-call keys. */
    public String artifactFileName(String fileName) {
        return sanitizeFileName(fileName);
    }

    public Path workspacePath(Long userId, Long sessionId) {
        if (userId == null || sessionId == null) {
            throw new IllegalArgumentException("User and session ids are required.");
        }
        Path workspace = root.resolve(String.valueOf(userId)).resolve(String.valueOf(sessionId)).normalize();
        if (!workspace.startsWith(root)) {
            throw new IllegalArgumentException("Invalid agent workspace path.");
        }
        return workspace;
    }

    public String toStoredRelativePath(Path workspace, Path artifact) {
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        Path normalizedArtifact = artifact.toAbsolutePath().normalize();
        if (!normalizedArtifact.startsWith(normalizedWorkspace) || normalizedArtifact.equals(normalizedWorkspace)) {
            throw new IllegalArgumentException("Artifact path is outside the session workspace.");
        }
        return normalizedWorkspace.relativize(normalizedArtifact).toString().replace('\\', '/');
    }

    public Path resolveStoredArtifact(Long userId, Long sessionId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Stored artifact path is missing.");
        }
        Path relative = Path.of(relativePath.replace('\\', '/')).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("Stored artifact path is invalid.");
        }
        Path workspace = workspacePath(userId, sessionId);
        Path resolved = workspace.resolve(relative).normalize();
        if (!resolved.startsWith(workspace) || resolved.equals(workspace)) {
            throw new IllegalArgumentException("Stored artifact path escapes the session workspace.");
        }
        return resolved;
    }

    private String sanitizeFileName(String fileName) {
        String value = fileName == null ? "" : fileName.trim();
        if (value.isBlank()) {
            value = "artifact";
        }
        value = Normalizer.normalize(value, Normalizer.Form.NFC);
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }

        StringBuilder safe = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            safe.appendCodePoint(isForbiddenFileNameCodePoint(codePoint) ? '_' : codePoint);
        }
        value = trimWindowsTrailingCharacters(safe.toString().trim());
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
            value = "artifact";
        }
        value = truncateFileName(value);

        String baseName = value;
        int extensionSeparator = value.indexOf('.');
        if (extensionSeparator >= 0) {
            baseName = value.substring(0, extensionSeparator);
        }
        if (WINDOWS_RESERVED_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
            value = "_" + value;
        }
        return value;
    }

    private boolean isForbiddenFileNameCodePoint(int codePoint) {
        return Character.isISOControl(codePoint)
                || codePoint == '<'
                || codePoint == '>'
                || codePoint == ':'
                || codePoint == '"'
                || codePoint == '/'
                || codePoint == '\\'
                || codePoint == '|'
                || codePoint == '?'
                || codePoint == '*';
    }

    private String trimWindowsTrailingCharacters(String value) {
        int end = value.length();
        while (end > 0) {
            char character = value.charAt(end - 1);
            if (character != ' ' && character != '.') {
                break;
            }
            end--;
        }
        return value.substring(0, end);
    }

    private String truncateFileName(String value) {
        if (value.length() <= MAX_FILE_NAME_LENGTH) {
            return value;
        }
        int extensionSeparator = value.lastIndexOf('.');
        String extension = extensionSeparator > 0 && value.length() - extensionSeparator <= 20
                ? value.substring(extensionSeparator)
                : "";
        int maxBaseLength = MAX_FILE_NAME_LENGTH - extension.length();
        int end = maxBaseLength;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + extension;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }
}
