package com.example.agent.service;

import com.example.agent.entity.AgentArtifact;
import com.example.agent.entity.AgentSession;
import com.example.agent.repository.AgentArtifactRepository;
import com.example.agent.repository.AgentSessionRepository;
import com.example.agent.tool.react.AgentWorkspaceService;
import com.example.agent.tool.react.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentArtifactServiceTest {

    @TempDir
    Path tempDir;

    private AgentArtifactRepository artifactRepository;
    private AgentSessionRepository sessionRepository;
    private AgentWorkspaceService workspaceService;
    private AgentRunRegistry runRegistry;
    private AgentArtifactService service;

    @BeforeEach
    void setUp() {
        artifactRepository = mock(AgentArtifactRepository.class);
        sessionRepository = mock(AgentSessionRepository.class);
        workspaceService = new AgentWorkspaceService(tempDir.resolve("workspace").toString());
        runRegistry = new AgentRunRegistry();
        service = new AgentArtifactService(artifactRepository, sessionRepository, workspaceService, runRegistry);

        AgentSession session = new AgentSession();
        session.setId(10L);
        session.setUserId(1L);
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));
        when(sessionRepository.findOwnedForUpdate(10L, 1L)).thenReturn(Optional.of(session));
        when(artifactRepository.save(any(AgentArtifact.class))).thenAnswer(invocation -> {
            AgentArtifact artifact = invocation.getArgument(0);
            artifact.setId(42L);
            return artifact;
        });
    }

    @Test
    void registersOnlyRelativeMetadataAndOpensOwnedFile() throws Exception {
        Path workspace = workspaceService.workspace(1L, 10L);
        Path file = workspaceService.createArtifactPath(workspace, "report.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3, 4});

        AgentArtifactService.ArtifactView view = service.register(
                new ToolExecutionContext(1L, 10L, 20L, workspace), "pdf_generation", file.toString());

        ArgumentCaptor<AgentArtifact> captor = ArgumentCaptor.forClass(AgentArtifact.class);
        verify(artifactRepository).save(captor.capture());
        AgentArtifact stored = captor.getValue();
        assertEquals("report.pdf", stored.getFileName());
        assertEquals("application/pdf", stored.getContentType());
        assertEquals(4L, stored.getSizeBytes());
        assertFalse(Path.of(stored.getRelativePath()).isAbsolute());
        assertFalse(stored.getRelativePath().contains(tempDir.toString()));

        assertEquals(42L, view.id());
        assertEquals("/agent/artifacts/42/content?download=true", view.downloadUrl());
        assertTrue(view.previewable());

        when(artifactRepository.findByIdAndUserId(42L, 1L)).thenReturn(Optional.of(stored));
        AgentArtifactService.ArtifactDownload download = service.openOwned(1L, 42L);
        assertEquals(file.toRealPath(), download.path());
        assertEquals(4L, download.sizeBytes());
    }

    @Test
    void rejectsFileOutsideSessionWorkspace() throws Exception {
        Path workspace = workspaceService.workspace(1L, 10L);
        Path outside = tempDir.resolve("outside.pdf");
        Files.write(outside, new byte[]{1});

        assertThrows(IllegalArgumentException.class, () -> service.register(
                new ToolExecutionContext(1L, 10L, 20L, workspace), "pdf_generation", outside.toString()));

        verify(artifactRepository, never()).save(any());
    }

    @Test
    void reusesMetadataWhenTheSameArtifactPathIsRegisteredAgain() throws Exception {
        Path workspace = workspaceService.workspace(1L, 10L);
        Path file = workspaceService.createIdempotentArtifactPath(workspace, "report.pdf", "request-20");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});
        String relativePath = workspaceService.toStoredRelativePath(workspace.toRealPath(), file.toRealPath());

        AgentArtifact existing = artifact(42L);
        existing.setRelativePath(relativePath);
        when(artifactRepository.findFirstByUserIdAndSessionIdAndMessageIdAndToolNameAndRelativePath(
                1L, 10L, 20L, "pdf_generation", relativePath)).thenReturn(Optional.of(existing));

        AgentArtifactService.ArtifactView view = service.register(
                new ToolExecutionContext(1L, 10L, 20L, workspace), "pdf_generation", file.toString());

        assertEquals(42L, view.id());
        verify(artifactRepository, never()).save(any());
    }

    @Test
    void hidesOtherUsersArtifactsAsNotFound() {
        when(artifactRepository.findByIdAndUserId(42L, 2L)).thenReturn(Optional.empty());

        assertThrows(AgentArtifactNotFoundException.class, () -> service.openOwned(2L, 42L));
    }

    @Test
    void returnsNotFoundWhenMetadataFileIsMissing() {
        AgentArtifact artifact = artifact(42L);
        artifact.setRelativePath("artifacts/does-not-exist/missing.pdf");
        when(artifactRepository.findByIdAndUserId(42L, 1L)).thenReturn(Optional.of(artifact));

        assertThrows(AgentArtifactNotFoundException.class, () -> service.openOwned(1L, 42L));
    }

    @Test
    void deletesOwnedArtifactFileMetadataAndEmptyStorageDirectory() throws Exception {
        Path workspace = workspaceService.workspace(1L, 10L);
        Path file = workspaceService.createArtifactPath(workspace, "报告.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1, 2, 3});

        AgentArtifact artifact = artifact(42L);
        artifact.setFileName("报告.pdf");
        artifact.setRelativePath(workspaceService.toStoredRelativePath(workspace.toRealPath(), file.toRealPath()));
        when(artifactRepository.findOwnedForUpdate(42L, 1L)).thenReturn(Optional.of(artifact));
        when(artifactRepository.findByIdAndUserId(42L, 1L)).thenReturn(Optional.of(artifact));

        service.deleteOwned(1L, 42L);

        verify(artifactRepository).delete(artifact);
        verify(artifactRepository).flush();
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent()));
        assertTrue(Files.isDirectory(workspace.resolve("artifacts")));
    }

    @Test
    void deletesStaleMetadataWhenPhysicalFileIsAlreadyMissing() {
        AgentArtifact artifact = artifact(42L);
        artifact.setRelativePath("artifacts/missing/report.pdf");
        when(artifactRepository.findOwnedForUpdate(42L, 1L)).thenReturn(Optional.of(artifact));
        when(artifactRepository.findByIdAndUserId(42L, 1L)).thenReturn(Optional.of(artifact));

        service.deleteOwned(1L, 42L);

        verify(artifactRepository).delete(artifact);
        verify(artifactRepository).flush();
    }

    @Test
    void keepsPhysicalFileWhileAnotherMetadataRowReferencesIt() throws Exception {
        Path workspace = workspaceService.workspace(1L, 10L);
        Path file = workspaceService.createArtifactPath(workspace, "shared.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{1});

        AgentArtifact artifact = artifact(42L);
        artifact.setRelativePath(workspaceService.toStoredRelativePath(workspace.toRealPath(), file.toRealPath()));
        when(artifactRepository.findOwnedForUpdate(42L, 1L)).thenReturn(Optional.of(artifact));
        when(artifactRepository.findByIdAndUserId(42L, 1L)).thenReturn(Optional.of(artifact));
        when(artifactRepository.existsByUserIdAndSessionIdAndRelativePathAndIdNot(
                1L, 10L, artifact.getRelativePath(), 42L)).thenReturn(true);

        service.deleteOwned(1L, 42L);

        assertTrue(Files.isRegularFile(file));
        verify(artifactRepository).delete(artifact);
    }

    @Test
    void rejectsTraversalMetadataWithoutTouchingOutsideFile() throws Exception {
        Path outside = tempDir.resolve("outside.pdf");
        Files.write(outside, new byte[]{9});
        AgentArtifact artifact = artifact(42L);
        artifact.setRelativePath("../../outside.pdf");
        when(artifactRepository.findOwnedForUpdate(42L, 1L)).thenReturn(Optional.of(artifact));
        when(artifactRepository.findByIdAndUserId(42L, 1L)).thenReturn(Optional.of(artifact));

        assertThrows(IllegalStateException.class, () -> service.deleteOwned(1L, 42L));

        assertTrue(Files.isRegularFile(outside));
    }

    @Test
    void hidesOtherUsersArtifactDeletionAsNotFound() {
        when(artifactRepository.findByIdAndUserId(42L, 2L)).thenReturn(Optional.empty());
        when(artifactRepository.findOwnedForUpdate(42L, 2L)).thenReturn(Optional.empty());

        assertThrows(AgentArtifactNotFoundException.class, () -> service.deleteOwned(2L, 42L));

        verify(artifactRepository, never()).delete(any());
    }

    @Test
    void rejectsDeletionWhileTheSessionStillHasAnActiveRun() {
        AgentArtifact artifact = artifact(42L);
        artifact.setRelativePath("artifacts/report.pdf");
        when(artifactRepository.findByIdAndUserId(42L, 1L)).thenReturn(Optional.of(artifact));

        try (AgentRunRegistry.Lease ignored = runRegistry.beginRun(1L, 10L)) {
            assertThrows(IllegalStateException.class, () -> service.deleteOwned(1L, 42L));
        }

        verify(artifactRepository, never()).delete(any());
    }

    private AgentArtifact artifact(Long id) {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setId(id);
        artifact.setUserId(1L);
        artifact.setSessionId(10L);
        artifact.setMessageId(20L);
        artifact.setToolName("pdf_generation");
        artifact.setFileName("missing.pdf");
        artifact.setContentType("application/pdf");
        artifact.setSizeBytes(123L);
        return artifact;
    }
}
