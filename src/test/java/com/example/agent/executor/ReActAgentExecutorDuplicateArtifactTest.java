package com.example.agent.executor;

import com.example.agent.entity.AgentToolSource;
import com.example.agent.service.AgentArtifactService;
import com.example.agent.service.AgentRunRegistry;
import com.example.agent.service.AgentStepService;
import com.example.agent.service.SkillLibraryService;
import com.example.agent.tool.react.AgentWorkspaceService;
import com.example.agent.tool.react.ReactTool;
import com.example.agent.tool.react.ReactToolRegistry;
import com.example.agent.tool.react.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReActAgentExecutorDuplicateArtifactTest {

    @TempDir
    Path tempDir;

    @Test
    void skipsRepeatedPdfToolExecutionAndReusesRegisteredArtifact() {
        ChatModel chatModel = mock(ChatModel.class);
        ReactToolRegistry toolRegistry = mock(ReactToolRegistry.class);
        AgentStepService stepService = mock(AgentStepService.class);
        AgentArtifactService artifactService = mock(AgentArtifactService.class);
        SkillLibraryService skillLibraryService = mock(SkillLibraryService.class);
        ReactTool pdfTool = mock(ReactTool.class);
        AgentWorkspaceService workspaceService = new AgentWorkspaceService(tempDir.toString());
        Path generatedPath = tempDir.resolve("generated.pdf");
        String firstAction = """
                {"type":"tool","plan":"generate report","toolName":"pdf_generation",
                 "arguments":{"fileName":"项目报告.pdf","content":"report"}}
                """;
        String renamedRetryAction = """
                {"type":"tool","plan":"retry report","toolName":"pdf_generation",
                 "arguments":{"fileName":"项目报告-重试.pdf","content":"report"}}
                """;

        when(skillLibraryService.catalog(1L)).thenReturn("");
        when(toolRegistry.toolDescriptions()).thenReturn("");
        when(toolRegistry.find("pdf_generation")).thenReturn(Optional.of(pdfTool));
        when(pdfTool.name()).thenReturn("pdf_generation");
        when(pdfTool.source()).thenReturn(AgentToolSource.LOCAL);
        when(pdfTool.execute(any(), anyMap())).thenReturn(
                ToolExecutionResult.success("PDF generated: 项目报告.pdf", generatedPath.toString()));
        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(firstAction), response(renamedRetryAction));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("已生成 PDF。")));

        AgentArtifactService.ArtifactView artifact = new AgentArtifactService.ArtifactView(
                42L, 20L, "pdf_generation", "项目报告.pdf", "application/pdf", 100L,
                "100 B", LocalDateTime.now(), true,
                "/agent/artifacts/42/content", "/agent/artifacts/42/content?download=true");
        when(artifactService.register(any(), any(), any())).thenReturn(artifact);

        ReActAgentExecutor executor = new ReActAgentExecutor(
                chatModel, toolRegistry, stepService, artifactService, new AgentRunRegistry(), workspaceService,
                skillLibraryService, new ObjectMapper(), 8);

        List<String> events = executor.executeStream(1L, 10L, 20L, "生成 PDF", List.of())
                .collectList().block();

        verify(pdfTool, times(1)).execute(any(), anyMap());
        verify(artifactService, times(1)).register(any(), any(), any());
        assertTrue(events.stream().anyMatch(event -> event.contains("Duplicate call skipped")));
        assertTrue(events.stream().anyMatch(event -> event.contains("\"phase\":\"FINAL\"")
                && event.contains("\"status\":\"SUCCESS\"")));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
