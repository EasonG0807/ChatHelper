package com.example.agent.service;

import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentRun;
import com.example.agent.entity.AgentRunStatus;
import com.example.agent.entity.AgentSession;
import com.example.agent.repository.AgentMessageRepository;
import com.example.agent.repository.AgentRunRepository;
import com.example.agent.repository.AgentSessionRepository;
import com.example.demo.service.ImageQuestionContext;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AgentRunService {

    private static final List<AgentRunStatus> ACTIVE_STATUSES =
            List.of(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING);
    private static final List<AgentRunStatus> TERMINAL_STATUSES = List.of(
            AgentRunStatus.SUCCEEDED,
            AgentRunStatus.FAILED,
            AgentRunStatus.CANCELLED,
            AgentRunStatus.INTERRUPTED
    );

    private final AgentRunRepository runRepository;
    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentSessionService sessionService;

    @Transactional
    public RunView create(Long userId,
                          Long sessionId,
                          String question,
                          ImageQuestionContext imageContext) {
        if (userId == null || sessionId == null) {
            throw new IllegalArgumentException("用户和会话不能为空。");
        }
        String safeQuestion = question == null ? "" : question.trim();
        if (safeQuestion.isBlank()) {
            throw new IllegalArgumentException("任务内容不能为空。");
        }
        AgentSession session = sessionRepository.findOwnedForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent session not found."));
        if (runRepository.existsByUserIdAndSessionIdAndStatusIn(userId, session.getId(), ACTIVE_STATUSES)) {
            throw new AgentRunConflictException("当前对话已有任务正在执行，请等待完成后再提交。");
        }

        String promptQuestion = imageContext == null
                ? safeQuestion
                : safeQuestion + "\n\n[图片输入]\n" + imageContext.description();
        String displayQuestion = imageContext == null
                ? safeQuestion
                : safeQuestion + "\n\n![用户上传图片](" + imageContext.webPath() + ")";
        AgentMessage userMessage = sessionService.saveMessage(session.getId(), "user", displayQuestion);

        AgentRun run = new AgentRun();
        run.setUserId(userId);
        run.setSessionId(session.getId());
        run.setUserMessageId(userMessage.getId());
        run.setQuestion(safeQuestion);
        run.setPromptQuestion(promptQuestion);
        run.setStatus(AgentRunStatus.QUEUED);
        return toView(runRepository.save(run));
    }

    @Transactional(readOnly = true)
    public RunView getOwned(Long userId, Long runId) {
        return toView(owned(userId, runId));
    }

    @Transactional(readOnly = true)
    public Optional<RunView> latestActive(Long userId, Long sessionId) {
        return runRepository.findFirstByUserIdAndSessionIdAndStatusInOrderByCreatedAtDescIdDesc(
                        userId, sessionId, ACTIVE_STATUSES)
                .map(this::toView);
    }

    @Transactional(readOnly = true)
    public Map<Long, SessionRunSummary> summaries(Long userId) {
        Map<Long, MutableSummary> grouped = new LinkedHashMap<>();
        // Active runs and unread terminal runs always have readAt == null.
        // Read history is irrelevant to the sidebar summary and must not be
        // reloaded on every refresh as the run table grows.
        for (AgentRun run : runRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId)) {
            MutableSummary summary = grouped.computeIfAbsent(run.getSessionId(), ignored -> new MutableSummary());
            if (summary.activeRunId == null && run.getStatus().isActive()) {
                summary.activeRunId = run.getId();
                summary.activeStatus = run.getStatus();
            }
            if (run.getStatus().isTerminal() && run.getReadAt() == null) {
                summary.unreadCount++;
                if (run.getStatus() != AgentRunStatus.SUCCEEDED) {
                    summary.failedUnread = true;
                }
            }
        }
        Map<Long, SessionRunSummary> result = new LinkedHashMap<>();
        grouped.forEach((sessionId, summary) -> result.put(sessionId, new SessionRunSummary(
                sessionId,
                summary.activeRunId,
                summary.activeStatus,
                summary.unreadCount,
                summary.failedUnread
        )));
        return result;
    }

    @Transactional
    public void markSessionRead(Long userId, Long sessionId) {
        sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent session not found."));
        runRepository.markRead(userId, sessionId, LocalDateTime.now(), TERMINAL_STATUSES);
    }

    @Transactional
    public Optional<RunView> markRunning(Long runId) {
        AgentRun run = runRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null || run.getStatus() != AgentRunStatus.QUEUED) {
            return Optional.empty();
        }
        run.setStatus(AgentRunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setErrorMessage(null);
        return Optional.of(toView(runRepository.save(run)));
    }

    @Transactional
    public RunView markSucceeded(Long runId) {
        AgentRun run = requireForUpdate(runId);
        if (!run.getStatus().isActive()) {
            return toView(run);
        }
        messageRepository.findFirstBySessionIdAndRoleAndIdGreaterThanOrderByIdDesc(
                        run.getSessionId(), "assistant", run.getUserMessageId())
                .map(AgentMessage::getId)
                .ifPresent(run::setAssistantMessageId);
        run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setCompletedAt(LocalDateTime.now());
        run.setErrorMessage(null);
        return toView(runRepository.save(run));
    }

    @Transactional
    public RunView markFailed(Long runId, String errorMessage) {
        AgentRun run = requireForUpdate(runId);
        if (run.getStatus().isTerminal()) {
            return toView(run);
        }
        run.setStatus(AgentRunStatus.FAILED);
        run.setCompletedAt(LocalDateTime.now());
        run.setErrorMessage(limit(errorMessage, 2000));
        return toView(runRepository.save(run));
    }

    @Transactional
    public List<RunView> interruptOrphanedRuns() {
        List<AgentRun> runs = runRepository.findByStatusOrderByCreatedAtAscIdAsc(AgentRunStatus.RUNNING);
        LocalDateTime now = LocalDateTime.now();
        for (AgentRun run : runs) {
            run.setStatus(AgentRunStatus.INTERRUPTED);
            run.setCompletedAt(now);
            run.setErrorMessage("服务重启，正在执行的任务无法继续，请重新提交。");
        }
        return runRepository.saveAll(runs).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<Long> queuedRunIds() {
        return runRepository.findByStatusOrderByCreatedAtAscIdAsc(AgentRunStatus.QUEUED).stream()
                .map(AgentRun::getId)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasActiveRun(Long userId, Long sessionId) {
        return runRepository.existsByUserIdAndSessionIdAndStatusIn(userId, sessionId, ACTIVE_STATUSES);
    }

    private AgentRun owned(Long userId, Long runId) {
        if (userId == null || runId == null) {
            throw new AgentRunNotFoundException("Agent run not found.");
        }
        return runRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new AgentRunNotFoundException("Agent run not found."));
    }

    private AgentRun requireForUpdate(Long runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new AgentRunNotFoundException("Agent run not found."));
    }

    private RunView toView(AgentRun run) {
        return new RunView(
                run.getId(),
                run.getUserId(),
                run.getSessionId(),
                run.getUserMessageId(),
                run.getAssistantMessageId(),
                run.getQuestion(),
                run.getPromptQuestion(),
                run.getStatus(),
                run.getLastEventSequence(),
                run.getErrorMessage(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getReadAt()
        );
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }

    private static final class MutableSummary {
        private Long activeRunId;
        private AgentRunStatus activeStatus;
        private long unreadCount;
        private boolean failedUnread;
    }

    public record RunView(Long id,
                          Long userId,
                          Long sessionId,
                          Long userMessageId,
                          Long assistantMessageId,
                          String question,
                          @JsonIgnore String promptQuestion,
                          AgentRunStatus status,
                          Long lastEventSequence,
                          String errorMessage,
                          LocalDateTime createdAt,
                          LocalDateTime startedAt,
                          LocalDateTime completedAt,
                          LocalDateTime readAt) {
    }

    public record SessionRunSummary(Long sessionId,
                                    Long activeRunId,
                                    AgentRunStatus activeStatus,
                                    long unreadCount,
                                    boolean failedUnread) {
    }
}
