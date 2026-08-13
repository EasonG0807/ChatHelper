package com.example.agent.service;

public class AgentRunNotFoundException extends IllegalArgumentException {
    public AgentRunNotFoundException(String message) {
        super(message);
    }
}
