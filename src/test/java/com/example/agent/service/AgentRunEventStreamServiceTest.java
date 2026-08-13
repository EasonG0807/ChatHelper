package com.example.agent.service;

import com.example.agent.entity.AgentRunEvent;
import com.example.agent.entity.AgentRunStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunEventStreamServiceTest {

    @Test
    void replaysPersistedEventsWithSseIdsUntilDone() {
        AgentRunService runService = mock(AgentRunService.class);
        AgentRunEventStore eventStore = mock(AgentRunEventStore.class);
        AgentRunEventStreamService service = new AgentRunEventStreamService(runService, eventStore);
        when(runService.getOwned(1L, 30L)).thenReturn(run());
        when(eventStore.listAfter(30L, 0L)).thenReturn(List.of(
                event(30L, 1L, "@@STEP@@{}"),
                event(30L, 2L, "[DONE]")
        ));

        var events = service.stream(1L, 30L, 0L).collectList().block();

        assertEquals(List.of("1", "2"), events.stream().map(item -> item.id()).toList());
        assertEquals("[DONE]", events.get(1).data());
    }

    @Test
    void committedEventsArePushedWithoutWaitingForDatabasePolling() {
        AgentRunService runService = mock(AgentRunService.class);
        AgentRunEventStore eventStore = mock(AgentRunEventStore.class);
        AgentRunEventStreamService service = new AgentRunEventStreamService(runService, eventStore);
        when(runService.getOwned(1L, 30L)).thenReturn(runningRun());
        when(eventStore.listAfter(30L, 0L)).thenReturn(List.of());
        when(eventStore.watch(30L)).thenReturn(reactor.core.publisher.Flux.just(List.of(
                event(30L, 1L, "@@STEP@@{}"),
                event(30L, 2L, "[DONE]")
        )));

        var events = service.stream(1L, 30L, 0L).collectList().block();

        assertEquals(List.of("1", "2"), events.stream().map(item -> item.id()).toList());
        assertEquals("[DONE]", events.get(1).data());
    }

    private AgentRunEvent event(Long runId, Long sequence, String data) {
        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(runId);
        event.setSequence(sequence);
        event.setData(data);
        return event;
    }

    private AgentRunService.RunView run() {
        return new AgentRunService.RunView(
                30L, 1L, 10L, 20L, 21L,
                "问题", "问题", AgentRunStatus.SUCCEEDED, 2L,
                null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), null);
    }

    private AgentRunService.RunView runningRun() {
        return new AgentRunService.RunView(
                30L, 1L, 10L, 20L, null,
                "问题", "问题", AgentRunStatus.RUNNING, 0L,
                null, LocalDateTime.now(), LocalDateTime.now(), null, null);
    }
}
