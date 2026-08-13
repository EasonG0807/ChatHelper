package com.example.agent.service;

import com.example.agent.entity.AgentRunEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class AgentRunEventStreamService {

    private static final Duration FALLBACK_INTERVAL = Duration.ofSeconds(10);
    private static final int EVENT_PAGE_SIZE = 500;

    private final AgentRunService runService;
    private final AgentRunEventStore eventStore;

    public Flux<ServerSentEvent<String>> stream(Long userId, Long runId, long afterSequence) {
        AgentRunService.RunView initial = runService.getOwned(userId, runId);
        AtomicLong cursor = new AtomicLong(Math.max(0L, afterSequence));
        AtomicBoolean completed = new AtomicBoolean(false);

        Flux<Wakeup> wakeups = initial.status().isTerminal()
                ? Flux.just(Wakeup.query())
                : Flux.merge(
                        Flux.just(Wakeup.query()),
                        eventStore.watch(runId).map(Wakeup::events),
                        Flux.interval(FALLBACK_INTERVAL).map(ignored -> Wakeup.query())
                );

        return wakeups
                .concatMap(wakeup -> wakeup.queryDatabase()
                        ? readPersisted(userId, runId, cursor)
                        : emitCommittedBatch(userId, runId, cursor, wakeup.committedEvents()))
                .doOnNext(event -> {
                    if ("[DONE]".equals(event.data())) {
                        completed.set(true);
                    }
                })
                .takeUntil(event -> "[DONE]".equals(event.data()))
                .doFinally(ignored -> {
                    if (completed.get()) {
                        eventStore.releaseSignal(runId);
                    }
                });
    }

    private Flux<ServerSentEvent<String>> emitCommittedBatch(Long userId,
                                                              Long runId,
                                                              AtomicLong cursor,
                                                              List<AgentRunEvent> committedEvents) {
        List<AgentRunEvent> fresh = committedEvents.stream()
                .filter(event -> event.getSequence() != null && event.getSequence() > cursor.get())
                .sorted(Comparator.comparingLong(AgentRunEvent::getSequence))
                .toList();
        if (fresh.isEmpty()) {
            return Flux.empty();
        }
        long expected = cursor.get() + 1L;
        for (AgentRunEvent event : fresh) {
            if (event.getSequence() != expected++) {
                return readPersisted(userId, runId, cursor);
            }
        }
        cursor.set(fresh.get(fresh.size() - 1).getSequence());
        return Flux.fromIterable(fresh).map(this::toSse);
    }

    private Flux<ServerSentEvent<String>> readPersisted(Long userId,
                                                         Long runId,
                                                         AtomicLong cursor) {
        return Mono.fromCallable(() -> eventStore.listAfter(runId, cursor.get()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(events -> {
                    if (events.isEmpty()) {
                        return terminalEventIfComplete(userId, runId, cursor);
                    }
                    cursor.set(events.get(events.size() - 1).getSequence());
                    Flux<ServerSentEvent<String>> page = Flux.fromIterable(events).map(this::toSse);
                    boolean done = events.stream().anyMatch(event -> "[DONE]".equals(event.getData()));
                    if (!done && events.size() == EVENT_PAGE_SIZE) {
                        return page.concatWith(Flux.defer(() -> readPersisted(userId, runId, cursor)));
                    }
                    if (!done) {
                        return page.concatWith(terminalEventIfComplete(userId, runId, cursor));
                    }
                    return page;
                });
    }

    private Flux<ServerSentEvent<String>> terminalEventIfComplete(Long userId,
                                                                   Long runId,
                                                                   AtomicLong cursor) {
        return Mono.fromCallable(() -> runService.getOwned(userId, runId))
                .subscribeOn(Schedulers.boundedElastic())
                .filter(current -> current.status().isTerminal()
                        && cursor.get() >= (current.lastEventSequence() == null
                        ? 0L : current.lastEventSequence()))
                .map(ignored -> ServerSentEvent.<String>builder("[DONE]")
                        .id(String.valueOf(cursor.get()))
                        .build())
                .flux();
    }

    private ServerSentEvent<String> toSse(AgentRunEvent event) {
        return ServerSentEvent.<String>builder(event.getData())
                .id(String.valueOf(event.getSequence()))
                .build();
    }

    private record Wakeup(boolean queryDatabase, List<AgentRunEvent> committedEvents) {

        private static Wakeup query() {
            return new Wakeup(true, List.of());
        }

        private static Wakeup events(List<AgentRunEvent> events) {
            return new Wakeup(false, events == null ? List.of() : events);
        }
    }
}
