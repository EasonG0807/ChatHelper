package com.example.agent.service.mcp;

import java.util.Optional;

/**
 * Storage boundary for MCP credentials. The default implementation encrypts
 * secrets into PostgreSQL. A HashiCorp Vault/KMS implementation can replace it
 * without changing MCP connection or tool execution code.
 */
public interface McpCredentialVault {

    void storeBearerToken(Long userId, Long connectionId, String plaintextToken);

    Optional<String> readBearerToken(Long userId, Long connectionId);

    boolean hasCredential(Long userId, Long connectionId);

    void delete(Long userId, Long connectionId);

    boolean isConfigured();

    String backendName();
}
