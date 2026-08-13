package com.example.agent.service;

import com.example.agent.executor.ReActAgentExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

@Service
public class AgentRunCoordinator {

    private static final int EVENT_BATCH_SIZE = 48;
    private static final Duration EVENT_FLUSH_INTERVAL = Duration.ofMillis(120);

    private final Executor executor;
    private final AgentRunService runService;
    private final AgentRunEventStore eventStore;
    private final AgentService agentService;
    private final AgentSessionService sessionService;
    private final AgentStepService stepService;
    private final ReActAgentExecutor reactAgentExecutor;

    public AgentRunCoordinator(@Qualifier("agentRunExecutor") Executor executor,
                               AgentRunService runService,
                               AgentRunEventStore eventStore,
                               AgentService agentService,
                               AgentSessionService sessionService,
                               AgentStepService stepService,
                               ReActAgentExecutor reactAgentExecutor) {
        this.executor = executor;
        this.runService = runService;
        this.eventStore = eventStore;
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.stepService = stepService;
        this.reactAgentExecutor = reactAgentExecutor;
    }

    public void dispatch(Long runId) {
        try {
            executor.execute(() -> execute(runId));
        } catch (RuntimeException rejected) {
            failBeforeStart(runId, "后台任务队列已满，请稍后重新提交。");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        for (AgentRunService.RunView interrupted : runService.interruptOrphanedRuns()) {
            String detail = "服务重启，任务已中断，请重新提交。";
            eventStore.append(interrupted.id(), reactAgentExecutor.terminalErrorEvent(
                    interrupted.userMessageId(), detail));
            eventStore.append(interrupted.id(), detail);
            sessionService.saveMessage(interrupted.sessionId(), "assistant", detail);
            eventStore.append(interrupted.id(), "[DONE]");
        }
        runService.queuedRunIds().forEach(this::dispatch);
    }

    private void execute(Long runId) {
        Optional<AgentRunService.RunView> claimed = runService.markRunning(runId);
        if (claimed.isEmpty()) {
            return;
        }
        AgentRunService.RunView run = claimed.get();
        try {
            agentService.executePrepared(
                            run.userId(),
                            run.sessionId(),
                            run.userMessageId(),
                            run.question(),
                            run.promptQuestion())
                    .filter(event -> !"[DONE]".equals(event))
                    .bufferTimeout(EVENT_BATCH_SIZE, EVENT_FLUSH_INTERVAL, Schedulers.boundedElastic())
                    .doOnNext(events -> eventStore.appendBatch(run.id(), compactEvents(events)))
                    .blockLast();
            runService.markSucceeded(run.id());
            eventStore.append(run.id(), "[DONE]");
        } catch (Throwable failure) {
            failRunning(run, failure);
        }
    }

    private void failRunning(AgentRunService.RunView run, Throwable failure) {
        String detail = safeError(failure);
        try {
            stepService.recordError(run.sessionId(), run.userMessageId(), detail);
        } catch (RuntimeException ignored) {
        }
        try {
            eventStore.append(run.id(), reactAgentExecutor.terminalErrorEvent(run.userMessageId(), detail));
            String answer = "任务执行失败：" + detail;
            eventStore.append(run.id(), answer);
            sessionService.saveMessage(run.sessionId(), "assistant", answer);
            runService.markFailed(run.id(), detail);
            eventStore.append(run.id(), "[DONE]");
        } catch (RuntimeException ignored) {
            runService.markFailed(run.id(), detail);
        }
    }

    private void failBeforeStart(Long runId, String detail) {
        try {
            AgentRunService.RunView run = runService.markFailed(runId, detail);
            eventStore.append(runId, reactAgentExecutor.terminalErrorEvent(run.userMessageId(), detail));
            String answer = "任务执行失败：" + detail;
            eventStore.append(runId, answer);
            sessionService.saveMessage(run.sessionId(), "assistant", answer);
            eventStore.append(runId, "[DONE]");
        } catch (RuntimeException ignored) {
        }
    }

    private String safeError(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000) + "…";
    }

    private List<String> compactEvents(List<String> events) {
        if (events == null || events.size() < 2) {
            return events == null ? List.of() : List.copyOf(events);
        }
        List<String> compacted = new ArrayList<>(events.size());
        List<String> answerDeltas = new ArrayList<>();
        for (String event : events) {
            if (reactAgentExecutor.answerDeltaText(event) != null) {
                answerDeltas.add(event);
                continue;
            }
            flushAnswerDeltas(compacted, answerDeltas);
            compacted.add(event);
        }
        flushAnswerDeltas(compacted, answerDeltas);
        return compacted;
    }

    private void flushAnswerDeltas(List<String> target, List<String> answerDeltas) {
        if (answerDeltas.isEmpty()) {
            return;
        }
        String merged = reactAgentExecutor.mergeAnswerDeltaEvents(answerDeltas);
        if (merged == null) {
            target.addAll(answerDeltas);
        } else {
            target.add(merged);
        }
        answerDeltas.clear();
    }
}
