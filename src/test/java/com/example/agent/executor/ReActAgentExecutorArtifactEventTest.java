package com.example.agent.executor;

import com.example.agent.service.AgentArtifactService;
import com.example.agent.service.AgentRunRegistry;
import com.example.agent.service.AgentStepService;
import com.example.agent.service.SkillLibraryService;
import com.example.agent.tool.react.AgentWorkspaceService;
import com.example.agent.tool.react.ReactToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                new AgentRunRegistry(),
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

    @Test
    void duplicatePdfKeyUsesCanonicalRequestedFileName() {
        ReActAgentExecutor executor = new ReActAgentExecutor(
                mock(ChatModel.class),
                mock(ReactToolRegistry.class),
                mock(AgentStepService.class),
                mock(AgentArtifactService.class),
                new AgentRunRegistry(),
                new AgentWorkspaceService(tempDir.toString()),
                mock(SkillLibraryService.class),
                new ObjectMapper().findAndRegisterModules(),
                8);
        Map<String, Object> firstArguments = new LinkedHashMap<>();
        firstArguments.put("fileName", "../项目:报告");
        firstArguments.put("content", "first version");
        Map<String, Object> retriedArguments = new LinkedHashMap<>();
        retriedArguments.put("fileName", "项目_报告.pdf");
        retriedArguments.put("content", "changed retry content");

        java.util.List<String> firstKeys = ReflectionTestUtils.invokeMethod(
                executor, "artifactCallKeys", "pdf_generation", firstArguments);
        java.util.List<String> retriedKeys = ReflectionTestUtils.invokeMethod(
                executor, "artifactCallKeys", "pdf_generation", retriedArguments);

        assertNotNull(firstKeys);
        assertTrue(firstKeys.stream().anyMatch(key -> key.endsWith("项目_报告.pdf")));
        assertTrue(firstKeys.stream().anyMatch(retriedKeys::contains));
    }

    @Test
    void terminalErrorEventIsStructured() {
        ReActAgentExecutor executor = new ReActAgentExecutor(
                mock(ChatModel.class), mock(ReactToolRegistry.class), mock(AgentStepService.class),
                mock(AgentArtifactService.class), new AgentRunRegistry(),
                new AgentWorkspaceService(tempDir.toString()),
                mock(SkillLibraryService.class), new ObjectMapper(), 8);

        String event = executor.terminalErrorEvent("connection failed");

        assertTrue(event.startsWith(ReActAgentExecutor.STEP_EVENT_PREFIX));
        assertTrue(event.contains("\"phase\":\"ERROR\""));
        assertTrue(event.contains("connection failed"));
    }

    @Test
    void streamsStructuredProgressAndRepairsMarkdownWithAuthoritativeFinalEvent() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ReactToolRegistry toolRegistry = mock(ReactToolRegistry.class);
        AgentStepService stepService = mock(AgentStepService.class);
        SkillLibraryService skillLibraryService = mock(SkillLibraryService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        when(skillLibraryService.catalog(1L)).thenReturn("");
        when(toolRegistry.toolDescriptions(1L)).thenReturn("");
        when(chatModel.call(any(Prompt.class))).thenReturn(response(
                "{\"type\":\"finish\",\"plan\":\"直接回答用户\"}"));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("**完"), response("成**")));

        ReActAgentExecutor executor = new ReActAgentExecutor(
                chatModel, toolRegistry, stepService, mock(AgentArtifactService.class),
                new AgentRunRegistry(), new AgentWorkspaceService(tempDir.toString()),
                skillLibraryService, mapper, 8);

        List<String> events = executor.executeStream(1L, 10L, 20L, "回答问题", List.of())
                .collectList().block();

        assertNotNull(events);
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(chatModel, times(1)).stream(any(Prompt.class));
        assertEquals("**完成**", events.stream()
                .map(executor::answerFinalMarkdown)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow());
        assertEquals("**完成**", events.stream()
                .map(executor::answerDeltaText)
                .filter(java.util.Objects::nonNull)
                .reduce("", String::concat));

        List<JsonNode> progress = events.stream()
                .filter(event -> event.startsWith(ReActAgentExecutor.STEP_EVENT_PREFIX))
                .map(event -> read(mapper, event, ReActAgentExecutor.STEP_EVENT_PREFIX))
                .toList();
        assertTrue(progress.stream().allMatch(event -> "20".equals(event.path("runId").asText())));
        assertTrue(progress.stream().allMatch(event -> "progress".equals(event.path("type").asText())));

        JsonNode planning = progress.stream().filter(event -> "PLANNING".equals(event.path("phase").asText()))
                .findFirst().orElseThrow();
        JsonNode plan = progress.stream().filter(event -> "PLAN".equals(event.path("phase").asText()))
                .findFirst().orElseThrow();
        assertEquals("RUNNING", planning.path("status").asText());
        assertEquals(planning.path("stageId").asText(), plan.path("stageId").asText());
        assertEquals(1, planning.path("reactStep").asInt());

        List<JsonNode> finalStages = progress.stream()
                .filter(event -> "FINAL".equals(event.path("phase").asText()))
                .toList();
        assertEquals(2, finalStages.size());
        assertEquals(finalStages.get(0).path("stageId").asText(), finalStages.get(1).path("stageId").asText());
        assertEquals("RUNNING", finalStages.get(0).path("status").asText());
        assertEquals("SUCCESS", finalStages.get(1).path("status").asText());
    }

    private JsonNode read(ObjectMapper mapper, String event, String prefix) {
        try {
            return mapper.readTree(event.substring(prefix.length()));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
