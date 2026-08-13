package com.example.agent.service;

import com.example.agent.entity.AgentRun;
import com.example.agent.entity.AgentRunEvent;
import com.example.agent.repository.AgentRunEventRepository;
import com.example.agent.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AgentRunEventStore {

    private final AgentRunRepository runRepository;
    private final AgentRunEventRepository eventRepository;
    private final Map<Long, Sinks.Many<List<AgentRunEvent>>> eventSignals = new ConcurrentHashMap<>();

    @Transactional
    public AgentRunEvent append(Long runId, String data) {
        if (data == null) {
            throw new IllegalArgumentException("Run event data is required.");
        }
        return appendBatch(runId, List.of(data)).get(0);
    }

    @Transactional
    public List<AgentRunEvent> appendBatch(Long runId, List<String> data) {
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        if (data.stream().anyMatch(item -> item == null)) {
            throw new IllegalArgumentException("Run event data is required.");
        }
        AgentRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new AgentRunNotFoundException("Agent run not found."));
        long sequence = run.getLastEventSequence() == null ? 0L : run.getLastEventSequence();
        List<AgentRunEvent> events = new ArrayList<>(data.size());
        for (String item : data) {
            AgentRunEvent event = new AgentRunEvent();
            event.setRunId(runId);
            event.setSequence(++sequence);
            event.setData(item);
            events.add(event);
        }
        events = eventRepository.saveAll(events);
        run.setLastEventSequence(sequence);
        runRepository.save(run);
        publishAfterCommit(runId, events, data.stream().anyMatch("[DONE]"::equals));
        return events;
    }

    @Transactional(readOnly = true)
    public List<AgentRunEvent> listAfter(Long runId, long afterSequence) {
        return eventRepository.findTop500ByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
                runId, Math.max(0L, afterSequence));
    }

    public Flux<List<AgentRunEvent>> watch(Long runId) {
        return signalFor(runId).asFlux();
    }

    public void releaseSignal(Long runId) {
        Sinks.Many<List<AgentRunEvent>> signal = eventSignals.remove(runId);
        if (signal != null) {
            synchronized (signal) {
                signal.tryEmitComplete();
            }
        }
    }

    private Sinks.Many<List<AgentRunEvent>> signalFor(Long runId) {
        return eventSignals.computeIfAbsent(runId,
                ignored -> Sinks.many().replay().latest());
    }

    private void publishAfterCommit(Long runId, List<AgentRunEvent> events, boolean terminal) {
        List<AgentRunEvent> committedEvents = List.copyOf(events);
        Runnable publish = () -> {
            Sinks.Many<List<AgentRunEvent>> signal = signalFor(runId);
            synchronized (signal) {
                signal.tryEmitNext(committedEvents);
                if (terminal) {
                    eventSignals.remove(runId, signal);
                    signal.tryEmitComplete();
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
