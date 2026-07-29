package com.example.agent.service;

import com.example.agent.entity.AgentArtifact;
import com.example.agent.repository.AgentArtifactRepository;
import com.example.agent.repository.AgentSessionRepository;
import com.example.agent.tool.react.AgentWorkspaceService;
import com.example.agent.tool.react.ToolExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AgentArtifactService {

    private final AgentArtifactRepository artifactRepository;
    private final AgentSessionRepository sessionRepository;
    private final AgentWorkspaceService workspaceService;
    private final AgentRunRegistry runRegistry;

    @Transactional
    public ArtifactView register(ToolExecutionContext context, String toolName, String artifactPath) {
        if (context == null || context.userId() == null || context.sessionId() == null) {
            throw new IllegalArgumentException("Artifact context is incomplete.");
        }
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new IllegalArgumentException("Artifact path is required.");
        }
        ensureOwnedSessionForUpdate(context.userId(), context.sessionId());

        Path workspace = context.workspaceRoot().toAbsolutePath().normalize();
        Path candidate = Path.of(artifactPath).toAbsolutePath().normalize();
        Path realWorkspace;
        Path realArtifact;
        try {
            realWorkspace = workspace.toRealPath();
            realArtifact = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Artifact file does not exist.", ex);
        }
        if (!realArtifact.startsWith(realWorkspace)
                || !Files.isRegularFile(realArtifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Artifact must be a regular file inside the session workspace.");
        }

        String storedToolName = limit(toolName, 120);
        String storedRelativePath = workspaceService.toStoredRelativePath(realWorkspace, realArtifact);
        AgentArtifact existing = artifactRepository
                .findFirstByUserIdAndSessionIdAndMessageIdAndToolNameAndRelativePath(
                        context.userId(), context.sessionId(), context.messageId(),
                        storedToolName, storedRelativePath)
                .orElse(null);
        if (existing != null) {
            return toView(existing);
        }

        AgentArtifact artifact = new AgentArtifact();
        artifact.setUserId(context.userId());
        artifact.setSessionId(context.sessionId());
        artifact.setMessageId(context.messageId());
        artifact.setToolName(storedToolName);
        artifact.setFileName(limit(realArtifact.getFileName().toString(), 255));
        artifact.setContentType(detectContentType(realArtifact));
        try {
            artifact.setSizeBytes(Files.size(realArtifact));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read artifact size.", ex);
        }
        artifact.setRelativePath(storedRelativePath);
        return toView(artifactRepository.save(artifact));
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> list(Long userId, Long sessionId) {
        ensureOwnedSession(userId, sessionId);
        return artifactRepository.findByUserIdAndSessionIdOrderByCreatedAtDescIdDesc(userId, sessionId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ArtifactDownload openOwned(Long userId, Long artifactId) {
        if (userId == null || artifactId == null) {
            throw notFound();
        }
        AgentArtifact artifact = artifactRepository.findByIdAndUserId(artifactId, userId)
                .orElseThrow(this::notFound);
        if (sessionRepository.findByIdAndUserId(artifact.getSessionId(), userId).isEmpty()) {
            throw notFound();
        }

        Path file = workspaceService.resolveStoredArtifact(
                artifact.getUserId(), artifact.getSessionId(), artifact.getRelativePath());
        try {
            Path realFile = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realWorkspace = workspaceService.workspacePath(userId, artifact.getSessionId()).toRealPath();
            if (!realFile.startsWith(realWorkspace)
                    || !Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
                throw notFound();
            }
            return new ArtifactDownload(
                    realFile,
                    artifact.getFileName(),
                    artifact.getContentType(),
                    Files.size(realFile),
                    isPreviewable(artifact.getContentType()));
        } catch (IOException | IllegalArgumentException ex) {
            throw notFound();
        }
    }

    @Transactional
    public void deleteOwned(Long userId, Long artifactId) {
        if (userId == null || artifactId == null) {
            throw notFound();
        }
        AgentArtifact candidate = artifactRepository.findByIdAndUserId(artifactId, userId)
                .orElseThrow(this::notFound);
        AgentRunRegistry.Lease mutationLease = runRegistry
                .tryBeginArtifactMutation(userId, candidate.getSessionId())
                .orElseThrow(() -> new IllegalStateException("Agent execution is still active."));
        boolean closeLeaseDirectly = !TransactionSynchronizationManager.isSynchronizationActive();
        if (!closeLeaseDirectly) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    mutationLease.close();
                }
            });
        }
        try {
            if (sessionRepository.findOwnedForUpdate(candidate.getSessionId(), userId).isEmpty()) {
                throw notFound();
            }
            AgentArtifact artifact = artifactRepository.findOwnedForUpdate(artifactId, userId)
                    .filter(locked -> Objects.equals(locked.getSessionId(), candidate.getSessionId()))
                    .orElseThrow(this::notFound);

            boolean sharedFile = artifactRepository.existsByUserIdAndSessionIdAndRelativePathAndIdNot(
                    artifact.getUserId(), artifact.getSessionId(), artifact.getRelativePath(), artifact.getId());
            artifactRepository.delete(artifact);
            artifactRepository.flush();

            if (!sharedFile) {
                try {
                    workspaceService.deleteStoredArtifact(
                            artifact.getUserId(), artifact.getSessionId(), artifact.getRelativePath());
                } catch (IllegalArgumentException invalidPath) {
                    throw new IllegalStateException("Artifact path validation failed.", invalidPath);
                }
            }
        } finally {
            if (closeLeaseDirectly) {
                mutationLease.close();
            }
        }
    }

    private void ensureOwnedSession(Long userId, Long sessionId) {
        if (userId == null || sessionId == null
                || sessionRepository.findByIdAndUserId(sessionId, userId).isEmpty()) {
            throw new IllegalArgumentException("Agent session not found.");
        }
    }

    private void ensureOwnedSessionForUpdate(Long userId, Long sessionId) {
        if (userId == null || sessionId == null
                || sessionRepository.findOwnedForUpdate(sessionId, userId).isEmpty()) {
            throw new IllegalArgumentException("Agent session not found.");
        }
    }

    private ArtifactView toView(AgentArtifact artifact) {
        long size = artifact.getSizeBytes() == null ? 0L : artifact.getSizeBytes();
        String contentUrl = "/agent/artifacts/" + artifact.getId() + "/content";
        return new ArtifactView(
                artifact.getId(),
                artifact.getMessageId(),
                artifact.getToolName(),
                artifact.getFileName(),
                artifact.getContentType(),
                size,
                formatSize(size),
                artifact.getCreatedAt(),
                isPreviewable(artifact.getContentType()),
                contentUrl,
                contentUrl + "?download=true");
    }

    private String detectContentType(Path path) {
        String detected = null;
        try {
            detected = Files.probeContentType(path);
        } catch (IOException ignored) {
        }
        if (detected == null || detected.isBlank()) {
            detected = MediaTypeFactory.getMediaType(path.getFileName().toString())
                    .map(MediaType::toString)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }
        return limit(detected.toLowerCase(Locale.ROOT), 128);
    }

    private boolean isPreviewable(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return MediaType.APPLICATION_PDF_VALUE.equals(normalized)
                || normalized.equals(MediaType.IMAGE_PNG_VALUE)
                || normalized.equals(MediaType.IMAGE_JPEG_VALUE)
                || normalized.equals(MediaType.IMAGE_GIF_VALUE)
                || normalized.equals("image/webp");
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private AgentArtifactNotFoundException notFound() {
        return new AgentArtifactNotFoundException("Artifact not found.");
    }

    public record ArtifactView(
            Long id,
            Long messageId,
            String toolName,
            String fileName,
            String contentType,
            long sizeBytes,
            String sizeLabel,
            LocalDateTime createdAt,
            boolean previewable,
            String contentUrl,
            String downloadUrl
    ) {
    }

    public record ArtifactDownload(
            Path path,
            String fileName,
            String contentType,
            long sizeBytes,
            boolean previewable
    ) {
    }
}
