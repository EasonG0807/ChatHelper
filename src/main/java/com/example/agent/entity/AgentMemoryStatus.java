package com.example.agent.entity;

/** Lifecycle state of one immutable memory fact version. */
public enum AgentMemoryStatus {
    ACTIVE,
    SUPERSEDED,
    INVALIDATED,
    CONFLICTED,
    EXPIRED
}
