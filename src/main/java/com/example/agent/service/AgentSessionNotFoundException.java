package com.example.agent.service;

public class AgentSessionNotFoundException extends RuntimeException {

    public AgentSessionNotFoundException(String message) {
        super(message);
    }
}
