package com.example.agent.service.mcp;

import com.example.agent.entity.McpCredential;
import com.example.agent.repository.McpCredentialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EncryptedDatabaseMcpCredentialVaultTest {

    @Test
    void encryptsAtRestAndDecryptsOnlyForTheBoundOwnerAndConnection() {
        McpCredentialRepository repository = mock(McpCredentialRepository.class);
        AtomicReference<McpCredential> stored = new AtomicReference<>();
        when(repository.findByConnectionIdAndOwnerUserId(7L, 3L))
                .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
        when(repository.save(any(McpCredential.class))).thenAnswer(invocation -> {
            McpCredential credential = invocation.getArgument(0);
            stored.set(credential);
            return credential;
        });
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MCP_CREDENTIAL_KEY_V1", key());
        EncryptedDatabaseMcpCredentialVault vault =
                new EncryptedDatabaseMcpCredentialVault(repository, environment, "v1");

        vault.storeBearerToken(3L, 7L, "secret-user-token");

        assertTrue(vault.isConfigured());
        assertEquals("v1", stored.get().getKeyId());
        assertNotEquals("secret-user-token", stored.get().getCiphertext());
        assertFalse(stored.get().getCiphertext().contains("secret-user-token"));
        assertEquals("secret-user-token", vault.readBearerToken(3L, 7L).orElseThrow());
    }

    @Test
    void reportsMissingMasterKeyBeforeAcceptingTokens() {
        McpCredentialRepository repository = mock(McpCredentialRepository.class);
        EncryptedDatabaseMcpCredentialVault vault = new EncryptedDatabaseMcpCredentialVault(
                repository, new MockEnvironment(), "v1");

        assertFalse(vault.isConfigured());
        assertThrows(McpCredentialConfigurationException.class,
                () -> vault.storeBearerToken(3L, 7L, "secret-user-token"));
    }

    private String key() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
