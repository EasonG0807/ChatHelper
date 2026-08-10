package com.example.agent.service.mcp;

import com.example.agent.entity.McpAuthType;
import com.example.agent.entity.McpConnection;
import com.example.agent.entity.McpConnectionStatus;
import com.example.agent.entity.McpTransportType;
import com.example.agent.repository.McpConnectionRepository;
import com.example.agent.repository.McpDiscoveredToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpConnectionRuntimeManagerTest {

    @Test
    void fallsBackFromStreamableHttpToSseAndPersistsDetectedTransport() {
        McpConnectionRepository connectionRepository = mock(McpConnectionRepository.class);
        McpDiscoveredToolRepository toolRepository = mock(McpDiscoveredToolRepository.class);
        McpCredentialVault credentialVault = mock(McpCredentialVault.class);
        McpRemoteClientFactory clientFactory = mock(McpRemoteClientFactory.class);
        McpSyncClient sseClient = mock(McpSyncClient.class);
        McpSchema.ListToolsResult listToolsResult = mock(McpSchema.ListToolsResult.class);
        McpConnection connection = connection(5L, 9L, "http://127.0.0.1:3000/mcp");

        when(connectionRepository.findByIdAndOwnerUserId(5L, 9L)).thenReturn(Optional.of(connection));
        when(connectionRepository.save(any(McpConnection.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(toolRepository.findByConnectionIdOrderByExposedNameAsc(5L)).thenReturn(List.of());
        when(listToolsResult.tools()).thenReturn(List.of());
        when(listToolsResult.nextCursor()).thenReturn(null);
        when(sseClient.listTools()).thenReturn(listToolsResult);
        when(clientFactory.connect(any(), eq(McpTransportType.STREAMABLE_HTTP), isNull()))
                .thenThrow(new IllegalStateException("405 Method Not Allowed"));
        when(clientFactory.connect(any(), eq(McpTransportType.SSE), isNull())).thenReturn(sseClient);

        McpConnectionRuntimeManager manager = new McpConnectionRuntimeManager(
                connectionRepository, toolRepository, credentialVault,
                new RemoteMcpUrlPolicy(true, true), clientFactory, new ObjectMapper());

        McpConnectionRuntimeManager.ConnectionTestResult result = manager.connectAndDiscover(9L, 5L);

        assertEquals(McpTransportType.SSE, result.transportType());
        assertEquals(McpTransportType.SSE, connection.getTransportType());
        assertEquals(McpConnectionStatus.CONNECTED, connection.getStatus());
        InOrder order = inOrder(clientFactory);
        order.verify(clientFactory).connect(any(), eq(McpTransportType.STREAMABLE_HTTP), isNull());
        order.verify(clientFactory).connect(any(), eq(McpTransportType.SSE), isNull());
        manager.closeAll();
    }

    @Test
    void usesEndpointSuffixAsThePreferredProbeOrder() {
        assertEquals(List.of(McpTransportType.STREAMABLE_HTTP, McpTransportType.SSE),
                McpConnectionRuntimeManager.preferredTransportOrder("/mcp"));
        assertEquals(List.of(McpTransportType.SSE, McpTransportType.STREAMABLE_HTTP),
                McpConnectionRuntimeManager.preferredTransportOrder("/custom/sse?tenant=demo"));
    }

    @Test
    void doesNotRetryAnotherTransportForAuthorizationFailuresAndRedactsTheToken() {
        McpConnectionRepository connectionRepository = mock(McpConnectionRepository.class);
        McpDiscoveredToolRepository toolRepository = mock(McpDiscoveredToolRepository.class);
        McpCredentialVault credentialVault = mock(McpCredentialVault.class);
        McpRemoteClientFactory clientFactory = mock(McpRemoteClientFactory.class);
        McpConnection connection = connection(6L, 9L, "http://127.0.0.1:3000/mcp");
        connection.setAuthType(McpAuthType.BEARER_TOKEN);

        when(connectionRepository.findByIdAndOwnerUserId(6L, 9L)).thenReturn(Optional.of(connection));
        when(connectionRepository.save(any(McpConnection.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(credentialVault.readBearerToken(9L, 6L)).thenReturn(Optional.of("top-secret-token"));
        when(clientFactory.connect(any(), eq(McpTransportType.STREAMABLE_HTTP), eq("top-secret-token")))
                .thenThrow(new IllegalStateException("initialization failed",
                        new IllegalStateException("HTTP 401 Unauthorized token=top-secret-token")));

        McpConnectionRuntimeManager manager = new McpConnectionRuntimeManager(
                connectionRepository, toolRepository, credentialVault,
                new RemoteMcpUrlPolicy(true, true), clientFactory, new ObjectMapper());

        assertThrows(IllegalArgumentException.class, () -> manager.connectAndDiscover(9L, 6L));

        assertEquals(McpConnectionStatus.REAUTH_REQUIRED, connection.getStatus());
        assertFalse(connection.getLastError().contains("top-secret-token"));
        verify(clientFactory, never()).connect(any(), eq(McpTransportType.SSE), anyString());
    }

    private McpConnection connection(Long id, Long userId, String serverUrl) {
        McpConnection connection = new McpConnection();
        connection.setId(id);
        connection.setOwnerUserId(userId);
        connection.setName("测试连接");
        connection.setServerUrl(serverUrl);
        connection.setAuthType(McpAuthType.NONE);
        connection.setEnabled(true);
        connection.setStatus(McpConnectionStatus.DISCONNECTED);
        return connection;
    }
}
