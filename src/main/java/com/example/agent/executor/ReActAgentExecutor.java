package com.example.agent.executor;

import com.example.agent.entity.AgentToolSource;
import com.example.agent.service.AgentArtifactService;
import com.example.agent.service.AgentRunRegistry;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams the whole ReAct run over one SSE channel:
 * progress, answer-delta, answer-final and heartbeat events are emitted as
 * structured JSON frames. Routing between direct answers and tool use belongs
 * to the planner itself — a finish action on step 1 behaves like a plain chat
 * reply.
 */
@Service
public class ReActAgentExecutor {

    public static final String STEP_EVENT_PREFIX = "@@STEP@@";
    public static final String ANSWER_DELTA_EVENT_PREFIX = "@@ANSWER_DELTA@@";
    public static final String ANSWER_FINAL_EVENT_PREFIX = "@@ANSWER_FINAL@@";
    public static final String HEARTBEAT_EVENT_PREFIX = "@@HEARTBEAT@@";

    private static final int MAX_RAW_MODEL_TEXT_LENGTH = 4000;
    private static final int MAX_EVENT_DETAIL_LENGTH = 400;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    private final ChatModel chatModel;
    private final ReactToolRegistry toolRegistry;
    private final AgentStepService stepService;
    private final AgentArtifactService artifactService;
    private final AgentRunRegistry runRegistry;
    private final AgentWorkspaceService workspaceService;
    private final SkillLibraryService skillLibraryService;
    private final ObjectMapper objectMapper;
    private final int maxSteps;

    public ReActAgentExecutor(@Qualifier("agentDeepSeekChatModel") ChatModel chatModel,
                              ReactToolRegistry toolRegistry,
                              AgentStepService stepService,
                              AgentArtifactService artifactService,
                              AgentRunRegistry runRegistry,
                              AgentWorkspaceService workspaceService,
                              SkillLibraryService skillLibraryService,
                              ObjectMapper objectMapper,
                              @Value("${agent.react.max-steps:8}") int maxSteps) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.stepService = stepService;
        this.artifactService = artifactService;
        this.runRegistry = runRegistry;
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
        return Flux.defer(() -> {
            StreamRun run = StreamRun.create(messageId);
            Flux<String> execution = Flux.<String>create(sink -> {
                try (AgentRunRegistry.Lease ignored = runRegistry.beginRun(userId, sessionId)) {
                    runLoop(sink, run, userId, sessionId, messageId, question, history);
                } catch (Exception ex) {
                    sink.error(ex);
                    return;
                }
                sink.complete();
            }).subscribeOn(Schedulers.boundedElastic());

            // Planner and tool calls are intentionally blocking. A heartbeat
            // keeps the SSE connection alive while no business event can be
            // emitted and stops automatically when the execution completes.
            return execution.publish(shared -> Flux.merge(
                    shared,
                    Flux.interval(HEARTBEAT_INTERVAL)
                            .map(ignored -> heartbeatEvent(run))
                            .takeUntilOther(shared.ignoreElements())
            ));
        });
    }

    private void runLoop(FluxSink<String> sink,
                         StreamRun run,
                         Long userId,
                         Long sessionId,
                         Long messageId,
                         String question,
                         List<Message> history) {
        Path workspace = workspaceService.workspace(userId, sessionId);
        ToolExecutionContext context = new ToolExecutionContext(userId, sessionId, messageId, workspace);
        String skillCatalog = skillLibraryService.catalog(userId);
        List<String> observations = new ArrayList<>();
        Map<String, CompletedArtifactCall> completedArtifactCalls = new LinkedHashMap<>();

        for (int step = 1; step <= maxSteps && !sink.isCancelled(); step++) {
            emitStep(sink, run, step, "PLANNING", null, "RUNNING", "第 " + step + " 步规划中…");
            ReActAction action = nextAction(userId, question, history, observations, step, skillCatalog);
            String plan = blankToDefault(action.plan(), "Decide the next action.");
            stepService.recordPlan(sessionId, messageId, "Step " + step + ": " + plan);
            emitStep(sink, run, step, "PLAN", null, "SUCCESS", plan);

            if (action.isFinish()) {
                streamFinalAnswer(sink, run, step, question, history, observations, plan, sessionId, messageId);
                return;
            }

            if (!action.isTool()) {
                String answer = "Unsupported model action type: " + action.type();
                stepService.recordError(sessionId, messageId, answer);
                emitStep(sink, run, step, "ERROR", null, "ERROR", answer);
                emitAnswerDelta(sink, run, answer);
                emitAnswerFinal(sink, run, answer);
                return;
            }

            ReactTool tool = toolRegistry.find(userId, action.toolName()).orElse(null);
            if (tool == null) {
                String observation = "Unknown or disabled tool: " + action.toolName() + ". Available tools: " + toolRegistry.list(userId).stream()
                        .map(ReactTool::name)
                        .toList();
                stepService.recordToolError(sessionId, messageId, action.toolName(), AgentToolSource.LOCAL,
                        toJson(action.arguments()), observation, 0);
                emitStep(sink, run, step, "TOOL_ERROR", action.toolName(), "ERROR", observation);
                observations.add(formatObservation(step, action.toolName(), false, observation));
                continue;
            }

            long startedAt = System.currentTimeMillis();
            String argumentsJson = toJson(action.arguments());
            stepService.recordToolCall(sessionId, messageId, tool.name(), tool.source(), argumentsJson);
            emitStep(sink, run, step, "TOOL_CALL", tool.name(), "RUNNING", argumentsJson);
            List<String> artifactCallKeys = artifactCallKeys(tool.name(), action.arguments());
            CompletedArtifactCall completedCall = artifactCallKeys.stream()
                    .map(completedArtifactCalls::get)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            if (completedCall != null) {
                String reusedObservation = completedCall.result().toObservation()
                        + "\n\nDuplicate PDF generation was skipped. The existing artifact was reused; do not call pdf_generation again for this file.";
                stepService.recordToolResult(sessionId, messageId, tool.name(), tool.source(),
                        argumentsJson, reusedObservation, 0);
                emitStep(sink, run, step, "TOOL_RESULT", tool.name(), "SUCCESS",
                        "0ms · Duplicate call skipped; existing artifact reused.", completedCall.artifact());
                observations.add(formatObservation(step, tool.name(), true, reusedObservation));
                streamFinalAnswer(sink, run, step, question, history, observations,
                        "A duplicate PDF request was skipped because the requested artifact had already been generated successfully.",
                        sessionId, messageId);
                return;
            }
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
                if (!artifactCallKeys.isEmpty() && artifact != null) {
                    CompletedArtifactCall completed = new CompletedArtifactCall(result, artifact);
                    artifactCallKeys.forEach(key -> completedArtifactCalls.put(key, completed));
                }
                stepService.recordToolResult(sessionId, messageId, tool.name(), tool.source(),
                        argumentsJson, result.toObservation(), latencyMs);
                emitStep(sink, run, step, "TOOL_RESULT", tool.name(), "SUCCESS",
                        latencyMs + "ms · " + result.toObservation(), artifact);
                if ("terminate".equals(tool.name())) {
                    observations.add(formatObservation(step, tool.name(), true, result.toObservation()));
                    streamFinalAnswer(sink, run, step, question, history, observations,
                            "Terminate tool requested final answer synthesis.", sessionId, messageId);
                    return;
                }
            } else {
                stepService.recordToolError(sessionId, messageId, tool.name(), tool.source(),
                        argumentsJson, result.errorMessage(), latencyMs);
                emitStep(sink, run, step, "TOOL_ERROR", tool.name(), "ERROR",
                        latencyMs + "ms · " + blankToDefault(result.errorMessage(), "tool failed"));
            }
            observations.add(formatObservation(step, tool.name(), result.success(), result.toObservation()));
        }

        stepService.recordError(sessionId, messageId, "Max ReAct steps exceeded.");
        streamFinalAnswer(sink, run, maxSteps, question, history, observations,
                "The agent reached the maximum number of ReAct steps. Provide the best possible partial answer.",
                sessionId, messageId);
    }

    private void streamFinalAnswer(FluxSink<String> sink,
                                   StreamRun run,
                                   int reactStep,
                                   String question,
                                   List<Message> history,
                                   List<String> observations,
                                   String completionReason,
                                   Long sessionId,
                                   Long messageId) {
        emitStep(sink, run, reactStep, "FINAL", null, "RUNNING", "生成最终回答…");
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
                    emitAnswerDelta(sink, run, chunk);
                }
            }
        } catch (RuntimeException ex) {
            if (full.isEmpty()) {
                String fallback = "抱歉，我暂时无法生成稳定的最终回答。请稍后重试，或把问题拆成更具体的一步。";
                full.append(fallback);
                emitAnswerDelta(sink, run, fallback);
            }
        }
        if (full.isEmpty()) {
            String fallback = "抱歉，我暂时无法生成稳定的最终回答。请稍后重试，或把问题拆成更具体的一步。";
            full.append(fallback);
            emitAnswerDelta(sink, run, fallback);
        }
        stepService.recordFinal(sessionId, messageId, full.toString());
        emitAnswerFinal(sink, run, full.toString());
        emitStep(sink, run, reactStep, "FINAL", null, "SUCCESS", "最终回答生成完成。");
    }

    /** Structured terminal error frame used by the outer streaming service. */
    public String terminalErrorEvent(String detail) {
        return progressEvent(StreamRun.create(), null, "ERROR", null, "ERROR",
                blankToDefault(detail, "Agent execution failed."), null);
    }

    public String terminalErrorEvent(Long messageId, String detail) {
        return progressEvent(StreamRun.create(messageId), null, "ERROR", null, "ERROR",
                blankToDefault(detail, "Agent execution failed."), null);
    }

    public static boolean isStructuredEvent(String value) {
        return value != null && (value.startsWith(STEP_EVENT_PREFIX)
                || value.startsWith(ANSWER_DELTA_EVENT_PREFIX)
                || value.startsWith(ANSWER_FINAL_EVENT_PREFIX)
                || value.startsWith(HEARTBEAT_EVENT_PREFIX));
    }

    public String answerDeltaText(String event) {
        return eventField(event, ANSWER_DELTA_EVENT_PREFIX, "text");
    }

    public String answerFinalMarkdown(String event) {
        return eventField(event, ANSWER_FINAL_EVENT_PREFIX, "markdown");
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
        emitStep(sink, StreamRun.create(), null, phase, tool, status, detail, artifact);
    }

    private void emitStep(FluxSink<String> sink,
                          StreamRun run,
                          Integer reactStep,
                          String phase,
                          String tool,
                          String status,
                          String detail) {
        emitStep(sink, run, reactStep, phase, tool, status, detail, null);
    }

    private void emitStep(FluxSink<String> sink,
                          StreamRun run,
                          Integer reactStep,
                          String phase,
                          String tool,
                          String status,
                          String detail,
                          AgentArtifactService.ArtifactView artifact) {
        if (sink.isCancelled()) {
            return;
        }
        sink.next(progressEvent(run, reactStep, phase, tool, status, detail, artifact));
    }

    private String progressEvent(StreamRun run,
                                 Integer reactStep,
                                 String phase,
                                 String tool,
                                 String status,
                                 String detail,
                                 AgentArtifactService.ArtifactView artifact) {
        long sequence = run.nextSequence();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "progress");
        event.put("runId", run.runId());
        event.put("sequence", sequence);
        if (reactStep != null) {
            event.put("reactStep", reactStep);
        }
        event.put("stageId", stageId(phase, reactStep, tool, sequence));
        event.put("phase", phase);
        if (tool != null) {
            event.put("tool", tool);
        }
        if (status != null) {
            event.put("status", status);
        }
        event.put("title", eventTitle(phase, status, tool, reactStep));
        event.put("detail", truncate(detail, MAX_EVENT_DETAIL_LENGTH));
        if (artifact != null) {
            event.put("artifact", artifact);
        }
        event.put("timestamp", System.currentTimeMillis());
        return STEP_EVENT_PREFIX + toJson(event);
    }

    private void emitAnswerDelta(FluxSink<String> sink, StreamRun run, String text) {
        if (sink.isCancelled() || text == null || text.isEmpty()) {
            return;
        }
        Map<String, Object> event = baseEvent(run, "answer-delta");
        event.put("text", text);
        sink.next(ANSWER_DELTA_EVENT_PREFIX + toJson(event));
    }

    private void emitAnswerFinal(FluxSink<String> sink, StreamRun run, String markdown) {
        if (sink.isCancelled()) {
            return;
        }
        Map<String, Object> event = baseEvent(run, "answer-final");
        event.put("markdown", markdown == null ? "" : markdown);
        sink.next(ANSWER_FINAL_EVENT_PREFIX + toJson(event));
    }

    private String heartbeatEvent(StreamRun run) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "heartbeat");
        event.put("runId", run.runId());
        event.put("timestamp", System.currentTimeMillis());
        return HEARTBEAT_EVENT_PREFIX + toJson(event);
    }

    private Map<String, Object> baseEvent(StreamRun run, String type) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("runId", run.runId());
        event.put("sequence", run.nextSequence());
        event.put("timestamp", System.currentTimeMillis());
        return event;
    }

    private String eventField(String event, String prefix, String field) {
        if (event == null || !event.startsWith(prefix)) {
            return null;
        }
        try {
            return objectMapper.readTree(event.substring(prefix.length())).path(field).asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stageId(String phase, Integer reactStep, String tool, long sequence) {
        String safePhase = phase == null ? "event" : phase.toLowerCase(Locale.ROOT);
        if ("planning".equals(safePhase) || "plan".equals(safePhase)) {
            return reactStep == null ? "plan-" + sequence : "plan-" + reactStep;
        }
        if (safePhase.startsWith("tool_")) {
            String toolPart = tool == null ? "tool" : tool.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
            return "tool-" + (reactStep == null ? sequence : reactStep) + "-" + toolPart;
        }
        if ("final".equals(safePhase)) {
            return "final";
        }
        if ("error".equals(safePhase)) {
            return "error";
        }
        return safePhase + "-" + sequence;
    }

    private String eventTitle(String phase, String status, String tool, Integer reactStep) {
        return switch (phase == null ? "" : phase) {
            case "PLANNING" -> "正在规划";
            case "PLAN" -> "规划完成";
            case "TOOL_CALL" -> "正在执行 " + toolDisplayName(tool);
            case "TOOL_RESULT" -> toolDisplayName(tool) + " 执行完成";
            case "TOOL_ERROR" -> toolDisplayName(tool) + " 执行失败";
            case "FINAL" -> "SUCCESS".equals(status) ? "回答生成完成" : "正在整理最终回答";
            case "ERROR" -> "任务执行失败";
            default -> "任务处理中";
        };
    }

    private String toolDisplayName(String tool) {
        if (tool == null || tool.isBlank()) {
            return "工具";
        }
        return switch (tool) {
            case "tool_list" -> "工具列表";
            case "document_list" -> "文档列表工具";
            case "rag_search" -> "RAG 文档检索";
            case "pdf_generation" -> "PDF 生成工具";
            case "web_search" -> "联网搜索工具";
            case "date_time" -> "日期时间工具";
            case "terminate" -> "结束工具";
            default -> tool;
        };
    }

    private ReActAction nextAction(Long userId,
                                   String question,
                                   List<Message> history,
                                   List<String> observations,
                                   int step,
                                   String skillCatalog) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt(userId, skillCatalog)));
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
                String repaired = repairActionJson(userId, raw, firstError.getMessage());
                return ReActAction.parseRequired(repaired, objectMapper);
            } catch (RuntimeException repairError) {
                return new ReActAction("finish",
                        "Planner output could not be parsed after repair; answer directly from available context.",
                        null, null, "");
            }
        }
    }

    private String systemPrompt(Long userId, String skillCatalog) {
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
                16. After pdf_generation succeeds, do not call it again for the same requested file or the same title/content. The artifact already exists; finish the task.
                17. Set pdf_generation.allowDuplicate=true only when the user explicitly asks for multiple separate PDFs containing the same material.

                Available tools:
                %s
                """.formatted(skillCatalog, toolRegistry.toolDescriptions(userId));
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

    private List<String> artifactCallKeys(String toolName, Map<String, Object> arguments) {
        if (!"pdf_generation".equals(toolName)) {
            return List.of();
        }
        Object allowDuplicate = arguments == null ? null : arguments.get("allowDuplicate");
        if (Boolean.TRUE.equals(allowDuplicate)
                || (allowDuplicate instanceof String value && Boolean.parseBoolean(value))) {
            return List.of();
        }
        Object rawFileName = arguments == null ? null : arguments.get("fileName");
        String fileName = rawFileName == null ? "agent-report.pdf" : String.valueOf(rawFileName).trim();
        if (fileName.isBlank()) {
            fileName = "agent-report.pdf";
        }
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            fileName += ".pdf";
        }
        String title = arguments == null ? "" : normalizeLogicalText(arguments.get("title"));
        String content = arguments == null ? "" : normalizeLogicalText(arguments.get("content"));
        return List.of(
                toolName + ":name:" + workspaceService.artifactFileName(fileName).toLowerCase(Locale.ROOT),
                toolName + ":payload:" + sha256(title + "\u0000" + content));
    }

    private String normalizeLogicalText(Object value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFC)
                .replaceAll("\\s+", " ")
                .trim();
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

    private String repairActionJson(Long userId, String rawText, String parseError) {
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
                """.formatted(toolRegistry.toolDescriptions(userId))));
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

    private record CompletedArtifactCall(
            ToolExecutionResult result,
            AgentArtifactService.ArtifactView artifact
    ) {
    }

    private record StreamRun(String runId, AtomicLong sequence) {

        private static StreamRun create() {
            return new StreamRun(UUID.randomUUID().toString(), new AtomicLong());
        }

        private static StreamRun create(Object runKey) {
            return new StreamRun(runKey == null ? UUID.randomUUID().toString() : String.valueOf(runKey),
                    new AtomicLong());
        }

        private long nextSequence() {
            return sequence.incrementAndGet();
        }
    }
}
