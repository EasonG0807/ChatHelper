package com.example.agent.service.mcp;

import com.example.agent.entity.McpTransportType;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds and initializes one remote MCP client without exposing transport details to business services. */
@Component
public class McpRemoteClientFactory {

    private final Duration requestTimeout;
    private final Duration initializationTimeout;

    public McpRemoteClientFactory(
            @Value("${agent.mcp.remote.request-timeout:60s}") Duration requestTimeout,
            @Value("${agent.mcp.remote.initialization-timeout:30s}") Duration initializationTimeout) {
        this.requestTimeout = requestTimeout;
        this.initializationTimeout = initializationTimeout;
    }

    public McpSyncClient connect(RemoteMcpUrlPolicy.ValidatedEndpoint endpoint,
                                 McpTransportType transportType,
                                 String bearerToken) {
        McpClientTransport transport = createTransport(endpoint, transportType, bearerToken);
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("rag-agent-user-mcp", "1.0.0"))
                .requestTimeout(requestTimeout)
                .initializationTimeout(initializationTimeout)
                .build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException ex) {
            close(client);
            throw ex;
        }
    }

    private McpClientTransport createTransport(RemoteMcpUrlPolicy.ValidatedEndpoint endpoint,
                                               McpTransportType transportType,
                                               String bearerToken) {
        if (transportType == McpTransportType.STREAMABLE_HTTP) {
            HttpClientStreamableHttpTransport.Builder builder =
                    HttpClientStreamableHttpTransport.builder(endpoint.baseUri())
                            .endpoint(endpoint.endpoint())
                            // User tools are refreshed explicitly, so an eager server-notification stream is unnecessary.
                            .openConnectionOnStartup(false)
                            .connectTimeout(initializationTimeout);
            if (hasText(bearerToken)) {
                builder.customizeRequest(request -> request.header("Authorization", "Bearer " + bearerToken));
            }
            return builder.build();
        }

        HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(endpoint.baseUri())
                .sseEndpoint(endpoint.endpoint())
                .connectTimeout(initializationTimeout);
        if (hasText(bearerToken)) {
            builder.customizeRequest(request -> request.header("Authorization", "Bearer " + bearerToken));
        }
        return builder.build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void close(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (RuntimeException ignored) {
            // Best effort cleanup. Remote errors and credentials must not be logged here.
        }
    }
}
