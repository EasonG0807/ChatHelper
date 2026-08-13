package com.example.agent.service;

public class AgentRunConflictException extends IllegalStateException {
    public AgentRunConflictException(String message) {
        super(message);
    }
}
