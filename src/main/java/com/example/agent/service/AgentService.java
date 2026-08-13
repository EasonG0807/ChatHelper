package com.example.agent.service;

import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentSession;
import com.example.agent.executor.ReActAgentExecutor;
import com.example.demo.service.ImageQuestionContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * One streaming pipeline for every agent request. Routing between a direct
 * reply and tool use is the planner's own first decision, so there is no
 * keyword gate here anymore.
 */
@Service
public class AgentService {

    private final AgentSessionService sessionService;
    private final AgentStepService stepService;
    private final ReActAgentExecutor reactAgentExecutor;
    private final AgentContextManager contextManager;
    private final AgentMemoryService memoryService;

    public AgentService(AgentSessionService sessionService,
                        AgentStepService stepService,
                        ReActAgentExecutor reactAgentExecutor,
                        AgentContextManager contextManager,
                        AgentMemoryService memoryService) {
        this.sessionService = sessionService;
        this.stepService = stepService;
        this.reactAgentExecutor = reactAgentExecutor;
        this.contextManager = contextManager;
        this.memoryService = memoryService;
    }

    public Flux<String> streamAsk(Long userId, Long sessionId, String question) {
        return streamAsk(userId, sessionId, question, null);
    }

    public Flux<String> streamAsk(Long userId, Long sessionId, String question, ImageQuestionContext imageContext) {
        String safeQuestion = question == null ? "" : question.trim();
        if (safeQuestion.isBlank()) {
            return Flux.just("Please enter a question for the AI agent.", "[DONE]");
        }
        String effectiveQuestion = imageContext == null
                ? safeQuestion
                : safeQuestion + "\n\n[图片输入]\n" + imageContext.description();

        AgentSession session = sessionService.getOrCreateSession(userId, sessionId);
        AgentMessage userMessage = sessionService.saveMessage(session.getId(), "user",
                imageContext == null ? safeQuestion : safeQuestion + "\n\n![用户上传图片](" + imageContext.webPath() + ")");
        return executePrepared(userId, session.getId(), userMessage.getId(), safeQuestion, effectiveQuestion)
                .onErrorResume(error -> {
                    stepService.recordError(session.getId(), userMessage.getId(), error.getMessage());
                    return Flux.just(
                            reactAgentExecutor.terminalErrorEvent(userMessage.getId(), error.getMessage()),
                            "Agent execution failed: " + error.getMessage(),
                            "[DONE]");
                });
    }

    /**
     * Executes a request whose user message and durable run record already
     * exist. The returned stream is owned by the background runner rather
     * than by an HTTP connection, so browser navigation cannot cancel it.
     */
    public Flux<String> executePrepared(Long userId,
                                        Long sessionId,
                                        Long userMessageId,
                                        String safeQuestion,
                                        String effectiveQuestion) {
        AgentSession session = sessionService.requireOwnedSession(userId, sessionId);
        AgentContextManager.ContextPack context = contextManager.build(
                session, userMessageId, effectiveQuestion);
        String contextualQuestion = context.contextualQuestion();
        List<Message> history = context.history();

        StringBuilder answer = new StringBuilder();
        return reactAgentExecutor
                .executeStream(userId, session.getId(), userMessageId, contextualQuestion, history)
                .doOnNext(event -> {
                    String delta = reactAgentExecutor.answerDeltaText(event);
                    if (delta != null) {
                        answer.append(delta);
                    }
                    String finalMarkdown = reactAgentExecutor.answerFinalMarkdown(event);
                    if (finalMarkdown != null) {
                        // answer-final is authoritative and repairs any
                        // incomplete or duplicated transport chunks.
                        answer.setLength(0);
                        answer.append(finalMarkdown);
                    }
                    if (!ReActAgentExecutor.isStructuredEvent(event)) {
                        // Backward-compatible fallback for a plain-text error
                        // or a legacy executor event.
                        answer.append(event);
                    }
                })
                .concatWith(Flux.defer(() -> {
                    String finalAnswer = answer.toString();
                    if (!finalAnswer.isBlank()) {
                        sessionService.saveMessage(session.getId(), "assistant", finalAnswer);
                        memoryService.scheduleExtraction(
                                userId, session.getId(), userMessageId, safeQuestion, finalAnswer);
                    }
                    return Flux.just("[DONE]");
                }));
    }
}
