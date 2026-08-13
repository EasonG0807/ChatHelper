package com.example.agent.service;

import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentRun;
import com.example.agent.entity.AgentRunStatus;
import com.example.agent.entity.AgentSession;
import com.example.agent.repository.AgentMessageRepository;
import com.example.agent.repository.AgentRunRepository;
import com.example.agent.repository.AgentSessionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunServiceTest {

    @Test
    void createsQueuedRunAfterPersistingUserMessage() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        AgentMessageRepository messageRepository = mock(AgentMessageRepository.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        AgentRunService service = new AgentRunService(
                runRepository, sessionRepository, messageRepository, sessionService);

        AgentSession session = new AgentSession();
        session.setId(10L);
        session.setUserId(1L);
        AgentMessage message = new AgentMessage();
        message.setId(20L);
        when(sessionRepository.findOwnedForUpdate(10L, 1L)).thenReturn(Optional.of(session));
        when(sessionService.saveMessage(10L, "user", "分析项目")).thenReturn(message);
        when(runRepository.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            run.setId(30L);
            return run;
        });

        AgentRunService.RunView run = service.create(1L, 10L, " 分析项目 ", null);

        assertEquals(30L, run.id());
        assertEquals(20L, run.userMessageId());
        assertEquals(AgentRunStatus.QUEUED, run.status());
        assertEquals("分析项目", run.promptQuestion());
        verify(runRepository).existsByUserIdAndSessionIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                anyCollection());
    }

    @Test
    void rejectsASecondActiveRunInTheSameSession() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        AgentRunService service = new AgentRunService(
                runRepository,
                sessionRepository,
                mock(AgentMessageRepository.class),
                mock(AgentSessionService.class));
        AgentSession session = new AgentSession();
        session.setId(10L);
        when(sessionRepository.findOwnedForUpdate(10L, 1L)).thenReturn(Optional.of(session));
        when(runRepository.existsByUserIdAndSessionIdAndStatusIn(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                anyCollection())).thenReturn(true);

        assertThrows(AgentRunConflictException.class,
                () -> service.create(1L, 10L, "第二个任务", null));
    }

    @Test
    void reportsRunningAndUnreadSessionsSeparately() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentRunService service = new AgentRunService(
                runRepository,
                mock(AgentSessionRepository.class),
                mock(AgentMessageRepository.class),
                mock(AgentSessionService.class));
        AgentRun running = run(3L, 1L, 10L, AgentRunStatus.RUNNING, null);
        AgentRun unreadSuccess = run(2L, 1L, 11L, AgentRunStatus.SUCCEEDED, null);
        AgentRun unreadFailure = run(1L, 1L, 11L, AgentRunStatus.FAILED, null);
        when(runRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of(running, unreadSuccess, unreadFailure));

        Map<Long, AgentRunService.SessionRunSummary> summaries = service.summaries(1L);

        assertEquals(3L, summaries.get(10L).activeRunId());
        assertEquals(2L, summaries.get(11L).unreadCount());
        assertEquals(true, summaries.get(11L).failedUnread());
    }

    private AgentRun run(Long id,
                         Long userId,
                         Long sessionId,
                         AgentRunStatus status,
                         LocalDateTime readAt) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setUserId(userId);
        run.setSessionId(sessionId);
        run.setUserMessageId(id + 100);
        run.setQuestion("q");
        run.setPromptQuestion("q");
        run.setStatus(status);
        run.setReadAt(readAt);
        return run;
    }
}
