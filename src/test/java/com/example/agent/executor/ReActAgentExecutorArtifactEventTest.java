package com.example.agent.executor;

import com.example.agent.service.AgentArtifactService;
import com.example.agent.service.AgentStepService;
import com.example.agent.service.SkillLibraryService;
import com.example.agent.tool.react.AgentWorkspaceService;
import com.example.agent.tool.react.ReactToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ReActAgentExecutorArtifactEventTest {

    @TempDir
    Path tempDir;

    @Test
    void toolResultEventCarriesStructuredArtifactWithoutServerPath() {
        ReActAgentExecutor executor = new ReActAgentExecutor(
                mock(ChatModel.class),
                mock(ReactToolRegistry.class),
                mock(AgentStepService.class),
                mock(AgentArtifactService.class),
                new AgentWorkspaceService(tempDir.toString()),
                mock(SkillLibraryService.class),
                new ObjectMapper().findAndRegisterModules(),
                8);
        AgentArtifactService.ArtifactView artifact = new AgentArtifactService.ArtifactView(
                42L, 20L, "pdf_generation", "report.pdf", "application/pdf", 1024L,
                "1.0 KB", LocalDateTime.of(2026, 7, 27, 10, 0), true,
                "/agent/artifacts/42/content", "/agent/artifacts/42/content?download=true");

        String event = Flux.<String>create(sink -> {
            ReflectionTestUtils.invokeMethod(executor, "emitStep", sink,
                    "TOOL_RESULT", "pdf_generation", "SUCCESS", "PDF generated", artifact);
            sink.complete();
        }).blockFirst();

        assertNotNull(event);
        assertTrue(event.startsWith(ReActAgentExecutor.STEP_EVENT_PREFIX));
        assertTrue(event.contains("\"artifact\""));
        assertTrue(event.contains("\"fileName\":\"report.pdf\""));
        assertTrue(event.contains("/agent/artifacts/42/content"));
        assertFalse(event.contains(tempDir.toString()));
        assertFalse(event.contains("artifactPath"));
        assertFalse(event.contains("relativePath"));
    }
}
