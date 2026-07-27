package com.example.agent.tool.react;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class AgentWorkspaceService {

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
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return value.isBlank() ? "artifact" : value;
    }
}
