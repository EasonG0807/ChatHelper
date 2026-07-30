package com.example.agent.entity;

/** Verification state kept separately from the fact version lifecycle. */
public enum AgentMemoryVerificationStatus {
    UNVERIFIED,
    VERIFIED,
    STALE,
    REJECTED
}
