package com.example.agent.service;

import com.example.agent.entity.AgentRunStatus;
import com.example.agent.executor.ReActAgentExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunCoordinatorTest {

    @Test
    void executorConstructorParameterSelectsTheAgentRunExecutor() throws Exception {
        var constructor = AgentRunCoordinator.class.getConstructor(
                Executor.class,
                AgentRunService.class,
                AgentRunEventStore.class,
                AgentService.class,
                AgentSessionService.class,
                AgentStepService.class,
                ReActAgentExecutor.class);

        Qualifier qualifier = constructor.getParameters()[0].getAnnotation(Qualifier.class);

        assertNotNull(qualifier);
        assertEquals("agentRunExecutor", qualifier.value());
    }

    @Test
    void backgroundRunnerPersistsEventsBeforeCompletingTheRun() {
        Executor directExecutor = Runnable::run;
        AgentRunService runService = mock(AgentRunService.class);
        AgentRunEventStore eventStore = mock(AgentRunEventStore.class);
        AgentService agentService = mock(AgentService.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        AgentStepService stepService = mock(AgentStepService.class);
        ReActAgentExecutor reactExecutor = mock(ReActAgentExecutor.class);
        AgentRunCoordinator coordinator = new AgentRunCoordinator(
                directExecutor, runService, eventStore, agentService,
                sessionService, stepService, reactExecutor);
        AgentRunService.RunView run = run();
        when(runService.markRunning(30L)).thenReturn(Optional.of(run));
        when(agentService.executePrepared(1L, 10L, 20L, "问题", "问题"))
                .thenReturn(Flux.just("@@STEP@@{}", "[DONE]"));

        coordinator.dispatch(30L);

        var ordered = inOrder(eventStore, runService);
        ordered.verify(eventStore).appendBatch(30L, java.util.List.of("@@STEP@@{}"));
        ordered.verify(runService).markSucceeded(30L);
        ordered.verify(eventStore).append(30L, "[DONE]");
        verify(agentService).executePrepared(1L, 10L, 20L, "问题", "问题");
    }

    @Test
    void consecutiveAnswerDeltasArePersistedAsOneBatchEvent() {
        Executor directExecutor = Runnable::run;
        AgentRunService runService = mock(AgentRunService.class);
        AgentRunEventStore eventStore = mock(AgentRunEventStore.class);
        AgentService agentService = mock(AgentService.class);
        ReActAgentExecutor reactExecutor = mock(ReActAgentExecutor.class);
        AgentRunCoordinator coordinator = new AgentRunCoordinator(
                directExecutor, runService, eventStore, agentService,
                mock(AgentSessionService.class), mock(AgentStepService.class), reactExecutor);
        AgentRunService.RunView run = run();
        String first = "@@ANSWER_DELTA@@{\"text\":\"你\"}";
        String second = "@@ANSWER_DELTA@@{\"text\":\"好\"}";
        String merged = "@@ANSWER_DELTA@@{\"text\":\"你好\"}";
        when(runService.markRunning(30L)).thenReturn(Optional.of(run));
        when(agentService.executePrepared(1L, 10L, 20L, "问题", "问题"))
                .thenReturn(Flux.just(first, second, "[DONE]"));
        when(reactExecutor.answerDeltaText(first)).thenReturn("你");
        when(reactExecutor.answerDeltaText(second)).thenReturn("好");
        when(reactExecutor.mergeAnswerDeltaEvents(java.util.List.of(first, second))).thenReturn(merged);

        coordinator.dispatch(30L);

        verify(eventStore).appendBatch(30L, java.util.List.of(merged));
    }

    private AgentRunService.RunView run() {
        return new AgentRunService.RunView(
                30L, 1L, 10L, 20L, null,
                "问题", "问题", AgentRunStatus.RUNNING, 0L,
                null, LocalDateTime.now(), LocalDateTime.now(), null, null);
    }
}
