package com.example.agent.executor;

import com.example.agent.entity.AgentToolSource;
import com.example.agent.service.AgentArtifactService;
import com.example.agent.service.AgentStepService;
import com.example.agent.service.SkillLibraryService;
import com.example.agent.tool.react.AgentWorkspaceService;
import com.example.agent.tool.react.ReactTool;
import com.example.agent.tool.react.ReactToolRegistry;
import com.example.agent.tool.react.ToolExecutionContext;
import com.example.agent.tool.react.ToolExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streams the whole ReAct run over one SSE channel:
 * step events are emitted as "@@STEP@@{json}" frames while the final answer
 * is streamed as plain token chunks. Routing between direct answers and tool
 * use belongs to the planner itself — a finish action on step 1 behaves like
 * a plain chat reply.
 */
@Service
public class ReActAgentExecutor {

    public static final String STEP_EVENT_PREFIX = "@@STEP@@";

    private static final int MAX_RAW_MODEL_TEXT_LENGTH = 4000;
    private static final int MAX_EVENT_DETAIL_LENGTH = 400;

    private final ChatModel chatModel;
    private final ReactToolRegistry toolRegistry;
    private final AgentStepService stepService;
    private final AgentArtifactService artifactService;
    private final AgentWorkspaceService workspaceService;
    private final SkillLibraryService skillLibraryService;
    private final ObjectMapper objectMapper;
    private final int maxSteps;

    public ReActAgentExecutor(@Qualifier("agentDeepSeekChatModel") ChatModel chatModel,
                              ReactToolRegistry toolRegistry,
                              AgentStepService stepService,
                              AgentArtifactService artifactService,
                              AgentWorkspaceService workspaceService,
                              SkillLibraryService skillLibraryService,
                              ObjectMapper objectMapper,
                              @Value("${agent.react.max-steps:8}") int maxSteps) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.stepService = stepService;
        this.artifactService = artifactService;
        this.workspaceService = workspaceService;
        this.skillLibraryService = skillLibraryService;
        this.objectMapper = objectMapper;
        this.maxSteps = Math.max(1, Math.min(maxSteps, 16));
    }

    public Flux<String> executeStream(Long userId,
                                      Long sessionId,
                                      Long messageId,
                                      String question,
                                      List<Message> history) {
        return Flux.<String>create(sink -> {
            try {
                runLoop(sink, userId, sessionId, messageId, question, history);
                sink.complete();
            } catch (Exception ex) {
                sink.error(ex);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void runLoop(FluxSink<String> sink,
                         Long userId,
                         Long sessionId,
                         Long messageId,
                         String question,
                         List<Message> history) {
        Path workspace = workspaceService.workspace(userId, sessionId);
        ToolExecutionContext context = new ToolExecutionContext(userId, sessionId, messageId, workspace);
        String skillCatalog = skillLibraryService.catalog(userId);
        List<String> observations = new ArrayList<>();

        for (int step = 1; step <= maxSteps && !sink.isCancelled(); step++) {
            emitStep(sink, "PLANNING", null, null, "第 " + step + " 步规划中…");
            ReActAction action = nextAction(question, history, observations, step, skillCatalog);
            String plan = blankToDefault(action.plan(), "Decide the next action.");
            stepService.recordPlan(sessionId, messageId, "Step " + step + ": " + plan);
            emitStep(sink, "PLAN", null, "SUCCESS", plan);

            if (action.isFinish()) {
                streamFinalAnswer(sink, question, history, observations, plan, sessionId, messageId);
                return;
            }

            if (!action.isTool()) {
                String answer = "Unsupported model action type: " + action.type();
                stepService.recordError(sessionId, messageId, answer);
                emitStep(sink, "ERROR", null, "ERROR", answer);
                sink.next(answer);
                return;
            }

            ReactTool tool = toolRegistry.find(action.toolName()).orElse(null);
            if (tool == null) {
                String observation = "Unknown or disabled tool: " + action.toolName() + ". Available tools: " + toolRegistry.list().stream()
                        .map(ReactTool::name)
                        .toList();
                stepService.recordToolError(sessionId, messageId, action.toolName(), AgentToolSource.LOCAL,
                        toJson(action.arguments()), observation, 0);
                emitStep(sink, "TOOL_ERROR", action.toolName(), "ERROR", observation);
                observations.add(formatObservation(step, action.toolName(), false, observation));
                continue;
            }

            long startedAt = System.currentTimeMillis();
            String argumentsJson = toJson(action.arguments());
            stepService.recordToolCall(sessionId, messageId, tool.name(), tool.source(), argumentsJson);
            emitStep(sink, "TOOL_CALL", tool.name(), "RUNNING", argumentsJson);
            ToolExecutionResult result;
            try {
                result = tool.execute(context, action.arguments());
            } catch (RuntimeException ex) {
                result = ToolExecutionResult.failure(ex.getMessage());
            }
            long latencyMs = System.currentTimeMillis() - startedAt;
            AgentArtifactService.ArtifactView artifact = null;
            if (result.success() && result.artifactPath() != null && !result.artifactPath().isBlank()) {
                try {
                    artifact = artifactService.register(context, tool.name(), result.artifactPath());
                } catch (RuntimeException registrationError) {
                    result = ToolExecutionResult.failure("The generated file could not be registered for delivery.");
                }
            }
            if (result.success()) {
                stepService.recordToolResult(sessionId, messageId, tool.name(), tool.source(),
                        argumentsJson, result.toObservation(), latencyMs);
                emitStep(sink, "TOOL_RESULT", tool.name(), "SUCCESS",
                        latencyMs + "ms · " + result.toObservation(), artifact);
                if ("terminate".equals(tool.name())) {
                    observations.add(formatObservation(step, tool.name(), true, result.toObservation()));
                    streamFinalAnswer(sink, question, history, observations,
                            "Terminate tool requested final answer synthesis.", sessionId, messageId);
                    return;
                }
            } else {
                stepService.recordToolError(sessionId, messageId, tool.name(), tool.source(),
                        argumentsJson, result.errorMessage(), latencyMs);
                emitStep(sink, "TOOL_ERROR", tool.name(), "ERROR",
                        latencyMs + "ms · " + blankToDefault(result.errorMessage(), "tool failed"));
            }
            observations.add(formatObservation(step, tool.name(), result.success(), result.toObservation()));
        }

        stepService.recordError(sessionId, messageId, "Max ReAct steps exceeded.");
        streamFinalAnswer(sink, question, history, observations,
                "The agent reached the maximum number of ReAct steps. Provide the best possible partial answer.",
                sessionId, messageId);
    }

    private void streamFinalAnswer(FluxSink<String> sink,
                                   String question,
                                   List<Message> history,
                                   List<String> observations,
                                   String completionReason,
                                   Long sessionId,
                                   Long messageId) {
        emitStep(sink, "FINAL", null, "RUNNING", "生成最终回答…");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("""
                You are the final answer synthesizer for this Agentic RAG system.

                Answer the user in Chinese with clear Markdown.
                Do not output ReAct JSON.
                Use the provided tool observations as factual evidence.
                If the observations contain [SKILL LOADED] instructions, follow their output format and style requirements when writing the answer.
                If observations are missing, failed, or insufficient, say so clearly and answer from the conversation context only where reasonable.
                If current-date, latest, weather, schedule, opening-hours, price, policy, news or availability claims are involved, distinguish verified current facts from assumptions.
                Preserve citation ids from RAG observations when present.
                When a file artifact was generated, tell the user it is available in the page's “产出” area. Never expose a server filesystem path.
                """));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage("""
                Current user request:
                %s

                ReAct completion reason:
                %s

                Tool observations collected in this run:
                %s

                Write the final user-facing answer now.
                """.formatted(
                question,
                blankToDefault(completionReason, "Enough information has been collected."),
                observations.isEmpty() ? "(none)" : String.join("\n\n", observations))));

        StringBuilder full = new StringBuilder();
        try {
            for (ChatResponse response : chatModel.stream(new Prompt(messages)).toIterable()) {
                if (sink.isCancelled()) {
                    break;
                }
                String chunk = extractText(response);
                if (!chunk.isEmpty()) {
                    full.append(chunk);
                    sink.next(chunk);
                }
            }
        } catch (RuntimeException ex) {
            if (full.isEmpty()) {
                String fallback = "抱歉，我暂时无法生成稳定的最终回答。请稍后重试，或把问题拆成更具体的一步。";
                full.append(fallback);
                sink.next(fallback);
            }
        }
        if (full.isEmpty()) {
            String fallback = "抱歉，我暂时无法生成稳定的最终回答。请稍后重试，或把问题拆成更具体的一步。";
            full.append(fallback);
            sink.next(fallback);
        }
        stepService.recordFinal(sessionId, messageId, full.toString());
    }

    private void emitStep(FluxSink<String> sink, String phase, String tool, String status, String detail) {
        emitStep(sink, phase, tool, status, detail, null);
    }

    private void emitStep(FluxSink<String> sink,
                          String phase,
                          String tool,
                          String status,
                          String detail,
                          AgentArtifactService.ArtifactView artifact) {
        if (sink.isCancelled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", phase);
        if (tool != null) {
            event.put("tool", tool);
        }
        if (status != null) {
            event.put("status", status);
        }
        event.put("detail", truncate(detail, MAX_EVENT_DETAIL_LENGTH));
        if (artifact != null) {
            event.put("artifact", artifact);
        }
        sink.next(STEP_EVENT_PREFIX + toJson(event));
    }

    private ReActAction nextAction(String question,
                                   List<Message> history,
                                   List<String> observations,
                                   int step,
                                   String skillCatalog) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt(skillCatalog)));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(userPrompt(question, observations, step)));
        ChatResponse response = chatModel.call(new Prompt(messages));
        String raw = extractText(response);
        try {
            return ReActAction.parseRequired(raw, objectMapper);
        } catch (IllegalArgumentException firstError) {
            try {
                String repaired = repairActionJson(raw, firstError.getMessage());
                return ReActAction.parseRequired(repaired, objectMapper);
            } catch (RuntimeException repairError) {
                return new ReActAction("finish",
                        "Planner output could not be parsed after repair; answer directly from available context.",
                        null, null, "");
            }
        }
    }

    private String systemPrompt(String skillCatalog) {
        return """
                You are a ReAct-style AI agent in this Java Spring system.
                You are also the router: for plain conversation, greetings, general knowledge, translation,
                writing or reasoning that needs no external facts, no user documents and no skills,
                output a finish action IMMEDIATELY at step 1 instead of calling any tool.

                You must respond with exactly one JSON object and no Markdown fences.
                The JSON must be valid. Escape newlines in string values as \\n.

                JSON schema for using a tool:
                {
                  "type": "tool",
                  "plan": "brief reason for this step",
                  "toolName": "one available tool name",
                  "arguments": {}
                }

                JSON schema for finishing:
                {
                  "type": "finish",
                  "plan": "why enough information has been collected for the final answer"
                }

                Skills (progressive disclosure):
                The skill library below shows only skill names and descriptions.
                A skill's full instructions (workflow, output templates, tool guidance) are loaded
                only when you call the use_skill tool with its exact skillName.
                - When the current task matches a skill description, call use_skill FIRST, before other tools.
                - Once a [SKILL LOADED] observation appears, follow its instructions for the remaining steps.
                - Load at most the skills that are relevant; several skills may be combined in one task.
                - Do not call use_skill again for a skill that is already loaded in the observations.

                Available skills:
                %s

                Rules:
                0. If the user asks what tools or capabilities you have, call tool_list.
                1. Use tools when external web information, uploaded documents, downloads, or PDF artifacts are needed.
                2. For uploaded documents, call document_list first if the target document id is unclear, then call rag_search.
                3. For public web research, call web_search first, then web_scraping for the most relevant URLs.
                4. For single-document questions, rag_search should include documentId.
                5. For current date/time questions, call date_time.
                6. For arithmetic questions, call calculator.
                7. Do not invent tool results. Base the final answer on observations.
                8. Finish as soon as the task is sufficiently answered.
                9. Never put the final user-facing answer in this JSON. Final answers are generated by a separate answer synthesizer.
                10. When observations contain rag_search evidence like [S1], [S2], preserve those citation ids in the final answer.
                11. For document-grounded answers, add a final "引用来源" section listing citation id, document title/id, chunk index and relevance when available.
                12. If rag_search reports answer confidence, include it briefly and explain when evidence is insufficient.
                13. If the user asks whether a previous answer, plan, report or conclusion is based on today, current, latest or real-time conditions, first call date_time.
                14. After date_time, call web_search when the previous answer depends on weather, opening hours, schedules, policy, prices, news, availability, travel, public facts or other external current conditions.
                15. If a needed tool is unavailable or fails, finish and let the answer synthesizer explain the limitation instead of inventing data.

                Available tools:
                %s
                """.formatted(skillCatalog, toolRegistry.toolDescriptions());
    }

    private String userPrompt(String question, List<String> observations, int step) {
        String joinedObservations = observations.isEmpty()
                ? "(none yet)"
                : String.join("\n\n", observations);
        return """
                Current user request:
                %s

                Previous observations:
                %s

                You are at ReAct step %d of %d. Choose the next tool action or finish.
                For follow-up questions about whether a previous answer is based on today/current/latest information, use tools to verify time and external current facts before finishing whenever those tools are available.
                """.formatted(question, joinedObservations, step, maxSteps);
    }

    private String repairActionJson(String rawText, String parseError) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("""
                You repair malformed ReAct planner output.
                Return exactly one valid JSON object and no Markdown fences.
                Do not answer the user. Do not include final prose.

                Valid tool action:
                {"type":"tool","plan":"brief reason","toolName":"one available tool name","arguments":{}}

                Valid finish action:
                {"type":"finish","plan":"why enough information has been collected"}

                Available tools:
                %s
                """.formatted(toolRegistry.toolDescriptions())));
        messages.add(new UserMessage("""
                Parse error:
                %s

                Original model output:
                %s

                Repair it into one valid ReAct JSON object.
                """.formatted(parseError, truncateRaw(rawText))));
        return extractText(chatModel.call(new Prompt(messages)));
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String formatObservation(int step, String toolName, boolean success, String observation) {
        return "Observation " + step + " from " + toolName + " (" + (success ? "success" : "failed") + "):\n" + observation;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String truncate(String value, int maxChars) {
        String safe = value == null ? "" : value;
        if (safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, maxChars) + "…";
    }

    private String truncateRaw(String value) {
        if (value == null || value.length() <= MAX_RAW_MODEL_TEXT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RAW_MODEL_TEXT_LENGTH) + "\n[TRUNCATED]";
    }
}
