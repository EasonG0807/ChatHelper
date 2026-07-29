package com.example.agent.service;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Coordinates long-running Agent executions with destructive session-file
 * mutations. Multiple runs may coexist, while deletion requires exclusivity.
 */
@Service
public class AgentRunRegistry {

    private final ConcurrentMap<RunKey, ReentrantReadWriteLock> sessionLocks = new ConcurrentHashMap<>();

    public Lease beginRun(Long userId, Long sessionId) {
        Lock readLock = lockFor(userId, sessionId).readLock();
        readLock.lock();
        return new LockLease(readLock);
    }

    public Optional<Lease> tryBeginArtifactMutation(Long userId, Long sessionId) {
        Lock writeLock = lockFor(userId, sessionId).writeLock();
        return writeLock.tryLock()
                ? Optional.of(new LockLease(writeLock))
                : Optional.empty();
    }

    private ReentrantReadWriteLock lockFor(Long userId, Long sessionId) {
        if (userId == null || sessionId == null) {
            throw new IllegalArgumentException("User and session ids are required.");
        }
        return sessionLocks.computeIfAbsent(
                new RunKey(userId, sessionId), ignored -> new ReentrantReadWriteLock(true));
    }

    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    private record RunKey(Long userId, Long sessionId) {
    }

    private static final class LockLease implements Lease {
        private final Lock lock;
        private boolean closed;

        private LockLease(Lock lock) {
            this.lock = lock;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            lock.unlock();
        }
    }
}
