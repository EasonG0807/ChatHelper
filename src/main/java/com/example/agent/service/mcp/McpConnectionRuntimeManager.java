package com.example.agent.service.mcp;

import com.example.agent.entity.McpAuthType;
import com.example.agent.entity.McpConnection;
import com.example.agent.entity.McpConnectionStatus;
import com.example.agent.entity.McpDiscoveredTool;
import com.example.agent.entity.McpTransportType;
import com.example.agent.repository.McpConnectionRepository;
import com.example.agent.repository.McpDiscoveredToolRepository;
import com.example.agent.tool.react.ToolExecutionContext;
import com.example.agent.tool.react.ToolExecutionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class McpConnectionRuntimeManager {

    private static final int MAX_ERROR_CHARS = 1000;

    private final McpConnectionRepository connectionRepository;
    private final McpDiscoveredToolRepository toolRepository;
    private final McpCredentialVault credentialVault;
    private final RemoteMcpUrlPolicy urlPolicy;
    private final McpRemoteClientFactory remoteClientFactory;
    private final ObjectMapper objectMapper;
    private final Map<Long, ClientHolder> clients = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> connectionLocks = new ConcurrentHashMap<>();

    public McpConnectionRuntimeManager(McpConnectionRepository connectionRepository,
                                       McpDiscoveredToolRepository toolRepository,
                                       McpCredentialVault credentialVault,
                                       RemoteMcpUrlPolicy urlPolicy,
                                       McpRemoteClientFactory remoteClientFactory,
                                       ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.toolRepository = toolRepository;
        this.credentialVault = credentialVault;
        this.urlPolicy = urlPolicy;
        this.remoteClientFactory = remoteClientFactory;
        this.objectMapper = objectMapper;
    }

    public ConnectionTestResult connectAndDiscover(Long userId, Long connectionId) {
        McpConnection connection = owned(userId, connectionId);
        if (!Boolean.TRUE.equals(connection.getEnabled())) {
            throw new IllegalArgumentException("该 MCP 连接已停用。");
        }
        ReentrantLock lock = connectionLocks.computeIfAbsent(connectionId, ignored -> new ReentrantLock());
        lock.lock();
        String bearerToken = null;
        McpSyncClient client = null;
        try {
            closeHolder(clients.remove(connectionId));
            RemoteMcpUrlPolicy.ValidatedEndpoint endpoint = urlPolicy.validate(connection.getServerUrl());
            if (connection.getAuthType() == McpAuthType.BEARER_TOKEN) {
                bearerToken = credentialVault.readBearerToken(userId, connectionId)
                        .orElseThrow(() -> new McpCredentialConfigurationException("该连接尚未配置 MCP Token。"));
            }

            ConnectedClient connected = connectAutomatically(endpoint, bearerToken);
            client = connected.client();
            List<McpSchema.Tool> remoteTools = listAllTools(client);
            Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
            for (McpSchema.Tool tool : remoteTools) {
                callbacks.put(tool.name(), new SyncMcpToolCallback(client, tool));
            }
            syncDiscoveredTools(connection, remoteTools);
            clients.put(connectionId, new ClientHolder(userId, connectionId, client, Map.copyOf(callbacks)));
            client = null; // ownership transferred to the holder

            connection.setStatus(McpConnectionStatus.CONNECTED);
            connection.setTransportType(connected.transportType());
            connection.setLastError(null);
            connection.setLastConnectedAt(LocalDateTime.now());
            connectionRepository.save(connection);
            return new ConnectionTestResult(connectionId, remoteTools.size(), connected.transportType(),
                    connection.getLastConnectedAt());
        } catch (RuntimeException ex) {
            closeClient(client);
            String safeError = safeError(ex, bearerToken);
            connection.setStatus(isAuthorizationError(safeError)
                    ? McpConnectionStatus.REAUTH_REQUIRED : McpConnectionStatus.ERROR);
            connection.setTransportType(null);
            connection.setLastError(safeError);
            connectionRepository.save(connection);
            throw new IllegalArgumentException("MCP 连接失败：" + safeError, ex);
        } finally {
            bearerToken = null;
            lock.unlock();
        }
    }

    private ConnectedClient connectAutomatically(RemoteMcpUrlPolicy.ValidatedEndpoint endpoint,
                                                  String bearerToken) {
        List<McpTransportType> order = preferredTransportOrder(endpoint.endpoint());
        RuntimeException firstFailure = null;
        McpTransportType firstType = null;
        for (McpTransportType transportType : order) {
            try {
                return new ConnectedClient(remoteClientFactory.connect(endpoint, transportType, bearerToken),
                        transportType);
            } catch (RuntimeException ex) {
                String error = safeError(ex, bearerToken);
                if (isAuthorizationError(error)) {
                    throw ex;
                }
                if (firstFailure == null) {
                    firstFailure = ex;
                    firstType = transportType;
                    continue;
                }
                throw new IllegalStateException("自动检测失败（"
                        + transportLabel(firstType) + "：" + safeError(firstFailure, bearerToken)
                        + "；" + transportLabel(transportType) + "：" + error + "）", ex);
            }
        }
        throw firstFailure == null ? new IllegalStateException("没有可用的 MCP 远程传输协议。") : firstFailure;
    }

    static List<McpTransportType> preferredTransportOrder(String endpoint) {
        String path = endpoint == null ? "" : endpoint.split("\\?", 2)[0].toLowerCase();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith("/sse")) {
            return List.of(McpTransportType.SSE, McpTransportType.STREAMABLE_HTTP);
        }
        return List.of(McpTransportType.STREAMABLE_HTTP, McpTransportType.SSE);
    }

    private String transportLabel(McpTransportType transportType) {
        return transportType == McpTransportType.SSE ? "SSE" : "Streamable HTTP";
    }

    public ToolExecutionResult execute(Long userId,
                                       Long connectionId,
                                       String remoteToolName,
                                       ToolExecutionContext context,
                                       Map<String, Object> arguments) {
        ReentrantLock lock = connectionLocks.computeIfAbsent(connectionId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            ClientHolder holder = clients.get(connectionId);
            if (holder == null || !holder.userId().equals(userId) || !holder.client().isInitialized()) {
                connectAndDiscover(userId, connectionId);
                holder = clients.get(connectionId);
            }
            if (holder == null || !holder.userId().equals(userId)) {
                return ToolExecutionResult.failure("MCP 连接不可用。");
            }
            ToolCallback callback = holder.callbacks().get(remoteToolName);
            if (callback == null) {
                return ToolExecutionResult.failure("MCP 工具已不存在，请重新发现工具。");
            }
            String input = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
            // Authentication and tenancy stay in the client transport/registry.
            // Do not forward internal user/session identifiers to an external MCP server.
            String output = callback.call(input);
            return ToolExecutionResult.success(redactStoredCredential(userId, connectionId, output));
        } catch (JsonProcessingException ex) {
            return ToolExecutionResult.failure("MCP 工具参数序列化失败。");
        } catch (RuntimeException ex) {
            ClientHolder failedHolder = clients.remove(connectionId);
            closeHolder(failedHolder);
            markRuntimeFailure(userId, connectionId, ex);
            return ToolExecutionResult.failure("MCP 工具调用失败："
                    + safeError(ex, storedCredential(userId, connectionId)));
        } finally {
            lock.unlock();
        }
    }

    public void disconnect(Long userId, Long connectionId) {
        ReentrantLock lock = connectionLocks.computeIfAbsent(connectionId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            owned(userId, connectionId);
            closeHolder(clients.remove(connectionId));
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    public void closeAll() {
        clients.values().forEach(this::closeHolder);
        clients.clear();
    }

    private List<McpSchema.Tool> listAllTools(McpSyncClient client) {
        List<McpSchema.Tool> result = new ArrayList<>();
        McpSchema.ListToolsResult page = client.listTools();
        while (page != null) {
            if (page.tools() != null) {
                result.addAll(page.tools());
            }
            if (page.nextCursor() == null || page.nextCursor().isBlank()) {
                break;
            }
            page = client.listTools(page.nextCursor());
        }
        return result;
    }

    private void syncDiscoveredTools(McpConnection connection, List<McpSchema.Tool> remoteTools) {
        List<McpDiscoveredTool> existing = toolRepository.findByConnectionIdOrderByExposedNameAsc(connection.getId());
        Map<String, McpDiscoveredTool> byRemoteName = new LinkedHashMap<>();
        existing.forEach(tool -> byRemoteName.put(tool.getRemoteName(), tool));
        Set<String> seen = new LinkedHashSet<>();
        for (McpSchema.Tool remoteTool : remoteTools) {
            if (remoteTool == null || remoteTool.name() == null || remoteTool.name().isBlank()) {
                continue;
            }
            seen.add(remoteTool.name());
            McpDiscoveredTool tool = byRemoteName.getOrDefault(remoteTool.name(), new McpDiscoveredTool());
            tool.setConnectionId(connection.getId());
            tool.setRemoteName(remoteTool.name());
            tool.setExposedName(McpToolNames.exposedName(connection.getId(), remoteTool.name()));
            tool.setDescription(remoteTool.description());
            tool.setInputSchema(writeSchema(remoteTool));
            if (tool.getId() == null) {
                tool.setEnabled(true);
            }
            toolRepository.save(tool);
        }
        existing.stream()
                .filter(tool -> !seen.contains(tool.getRemoteName()))
                .forEach(toolRepository::delete);
    }

    private String writeSchema(McpSchema.Tool tool) {
        try {
            return tool.inputSchema() == null ? "{}" : objectMapper.writeValueAsString(tool.inputSchema());
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private void markRuntimeFailure(Long userId, Long connectionId, RuntimeException ex) {
        connectionRepository.findByIdAndOwnerUserId(connectionId, userId).ifPresent(connection -> {
            String error = safeError(ex, null);
            connection.setStatus(isAuthorizationError(error)
                    ? McpConnectionStatus.REAUTH_REQUIRED : McpConnectionStatus.ERROR);
            connection.setLastError(error);
            connectionRepository.save(connection);
        });
    }

    private McpConnection owned(Long userId, Long connectionId) {
        if (userId == null || connectionId == null) {
            throw new IllegalArgumentException("用户和 MCP 连接不能为空。");
        }
        return connectionRepository.findByIdAndOwnerUserId(connectionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("MCP 连接不存在或不属于当前用户。"));
    }

    private String safeError(Throwable throwable, String secret) {
        Set<String> messages = new LinkedHashSet<>();
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 6; depth++, current = current.getCause()) {
            String currentMessage = current.getMessage();
            messages.add(currentMessage == null || currentMessage.isBlank()
                    ? current.getClass().getSimpleName() : currentMessage);
        }
        String message = messages.isEmpty() ? "未知错误" : String.join(" → ", messages);
        if (secret != null && !secret.isBlank()) {
            message = message.replace(secret, "***");
        }
        message = message.replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", "$1***")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s,;]+", "$1***")
                .replaceAll("[\\r\\n\\t]+", " ")
                .strip();
        return message.length() > MAX_ERROR_CHARS ? message.substring(0, MAX_ERROR_CHARS) : message;
    }

    private String redactStoredCredential(Long userId, Long connectionId, String value) {
        String secret = storedCredential(userId, connectionId);
        if (value == null || secret == null || secret.isBlank()) {
            return value;
        }
        return value.replace(secret, "***");
    }

    private String storedCredential(Long userId, Long connectionId) {
        try {
            return credentialVault.readBearerToken(userId, connectionId).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isAuthorizationError(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        return lower.contains("401") || lower.contains("unauthorized") || lower.contains("forbidden")
                || lower.contains("invalid token");
    }

    private void closeHolder(ClientHolder holder) {
        if (holder != null) {
            closeClient(holder.client());
        }
    }

    private void closeClient(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } catch (RuntimeException ignored) {
            // Best effort cleanup; never copy remote errors or credentials to logs.
        }
    }

    private record ClientHolder(Long userId, Long connectionId, McpSyncClient client,
                                Map<String, ToolCallback> callbacks) {
    }

    private record ConnectedClient(McpSyncClient client, McpTransportType transportType) {
    }

    public record ConnectionTestResult(Long connectionId, int discoveredToolCount,
                                       McpTransportType transportType, LocalDateTime connectedAt) {
    }
}
