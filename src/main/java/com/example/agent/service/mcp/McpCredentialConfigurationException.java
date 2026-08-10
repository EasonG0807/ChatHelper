package com.example.agent.service.mcp;

public class McpCredentialConfigurationException extends IllegalStateException {

    public McpCredentialConfigurationException(String message) {
        super(message);
    }

    public McpCredentialConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
