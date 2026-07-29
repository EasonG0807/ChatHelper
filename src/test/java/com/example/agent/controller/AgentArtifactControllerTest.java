package com.example.agent.controller;

import com.example.agent.service.AgentArtifactNotFoundException;
import com.example.agent.service.AgentArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentArtifactControllerTest {

    @TempDir
    Path tempDir;

    private AgentArtifactService artifactService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        artifactService = mock(AgentArtifactService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentArtifactController(artifactService)).build();
    }

    @Test
    void requiresLoginForListAndContent() throws Exception {
        mockMvc.perform(get("/agent/artifacts").param("sessionId", "10"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/agent/artifacts/42/content"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/agent/artifacts/42"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsSafeArtifactViewWithoutStoragePath() throws Exception {
        AgentArtifactService.ArtifactView view = new AgentArtifactService.ArtifactView(
                42L, 20L, "pdf_generation", "report.pdf", "application/pdf", 4L,
                "4 B", LocalDateTime.of(2026, 7, 27, 10, 0), true,
                "/agent/artifacts/42/content", "/agent/artifacts/42/content?download=true");
        when(artifactService.list(1L, 10L)).thenReturn(List.of(view));

        mockMvc.perform(get("/agent/artifacts").param("sessionId", "10").sessionAttr("uid", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("report.pdf"))
                .andExpect(jsonPath("$[0].contentType").value("application/pdf"))
                .andExpect(content().string(not(containsString("relativePath"))))
                .andExpect(content().string(not(containsString("artifactPath"))));
    }

    @Test
    void downloadsOwnedPdfWithPrivateHeaders() throws Exception {
        Path file = tempDir.resolve("report.pdf");
        byte[] bytes = new byte[]{1, 2, 3, 4};
        Files.write(file, bytes);
        when(artifactService.openOwned(1L, 42L)).thenReturn(new AgentArtifactService.ArtifactDownload(
                file, "报告.pdf", "application/pdf", bytes.length, true));

        mockMvc.perform(get("/agent/artifacts/42/content")
                        .param("download", "true")
                        .sessionAttr("uid", 1L))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void returnsNotFoundForUnownedArtifact() throws Exception {
        when(artifactService.openOwned(2L, 42L))
                .thenThrow(new AgentArtifactNotFoundException("Artifact not found."));

        mockMvc.perform(get("/agent/artifacts/42/content").sessionAttr("uid", 2L))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesOwnedArtifact() throws Exception {
        mockMvc.perform(delete("/agent/artifacts/42").sessionAttr("uid", 1L))
                .andExpect(status().isNoContent());

        verify(artifactService).deleteOwned(1L, 42L);
    }

    @Test
    void returnsNotFoundWhenDeletingUnownedArtifact() throws Exception {
        doThrow(new AgentArtifactNotFoundException("Artifact not found."))
                .when(artifactService).deleteOwned(2L, 42L);

        mockMvc.perform(delete("/agent/artifacts/42").sessionAttr("uid", 2L))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsConflictWithoutLeakingPathWhenFileCannotBeDeleted() throws Exception {
        doThrow(new IllegalStateException("D:/private/workspace/report.pdf is locked"))
                .when(artifactService).deleteOwned(1L, 42L);

        mockMvc.perform(delete("/agent/artifacts/42").sessionAttr("uid", 1L))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("D:/private"))));
    }
}
