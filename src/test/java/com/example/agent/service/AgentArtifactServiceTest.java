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
    private AgentArtifactService service;

    @BeforeEach
    void setUp() {
        artifactRepository = mock(AgentArtifactRepository.class);
        sessionRepository = mock(AgentSessionRepository.class);
        workspaceService = new AgentWorkspaceService(tempDir.resolve("workspace").toString());
        service = new AgentArtifactService(artifactRepository, sessionRepository, workspaceService);

        AgentSession session = new AgentSession();
        session.setId(10L);
        session.setUserId(1L);
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));
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
    void hidesOtherUsersArtifactsAsNotFound() {
        when(artifactRepository.findByIdAndUserId(42L, 2L)).thenReturn(Optional.empty());

        assertThrows(AgentArtifactNotFoundException.class, () -> service.openOwned(2L, 42L));
    }

    @Test
    void returnsNotFoundWhenMetadataFileIsMissing() {
        AgentArtifact artifact = new AgentArtifact();
        artifact.setId(42L);
        artifact.setUserId(1L);
        artifact.setSessionId(10L);
        artifact.setFileName("missing.pdf");
        artifact.setContentType("application/pdf");
        artifact.setSizeBytes(123L);
        artifact.setRelativePath("artifacts/does-not-exist/missing.pdf");
        when(artifactRepository.findByIdAndUserId(42L, 1L)).thenReturn(Optional.of(artifact));

        assertThrows(AgentArtifactNotFoundException.class, () -> service.openOwned(1L, 42L));
    }
}
