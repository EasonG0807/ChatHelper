package com.example.agent.entity;

public enum AgentRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
