package com.example.agent.service.mcp;

import com.example.agent.entity.McpAuthType;
import com.example.agent.entity.McpConnection;
import com.example.agent.entity.McpConnectionStatus;
import com.example.agent.entity.McpDiscoveredTool;
import com.example.agent.repository.McpConnectionRepository;
import com.example.agent.repository.McpDiscoveredToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class McpConnectionService {

    private static final Pattern CONNECTION_NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._ -]{1,63}");

    private final McpConnectionRepository connectionRepository;
    private final McpDiscoveredToolRepository toolRepository;
    private final McpCredentialVault credentialVault;
    private final McpConnectionRuntimeManager runtimeManager;
    private final RemoteMcpUrlPolicy urlPolicy;

    @Value("${agent.mcp.user.max-connections:10}")
    private int maxConnections;

    public McpConnection create(Long userId,
                                String name,
                                String serverUrl,
                                McpAuthType authType,
                                String bearerToken) {
        requireUser(userId);
        String normalizedName = validateName(name);
        if (connectionRepository.countByOwnerUserId(userId) >= maxConnections) {
            throw new IllegalArgumentException("每个用户最多可创建 " + maxConnections + " 个 MCP 连接。");
        }
        if (connectionRepository.findByOwnerUserIdAndNameIgnoreCase(userId, normalizedName).isPresent()) {
            throw new IllegalArgumentException("你已经创建了同名 MCP 连接。");
        }
        RemoteMcpUrlPolicy.ValidatedEndpoint endpoint = urlPolicy.validate(serverUrl);
        McpAuthType normalizedAuth = authType == null ? McpAuthType.NONE : authType;
        if (normalizedAuth == McpAuthType.BEARER_TOKEN && (bearerToken == null || bearerToken.isBlank())) {
            throw new IllegalArgumentException("Bearer Token 连接必须填写 Token。");
        }
        if (normalizedAuth == McpAuthType.BEARER_TOKEN && !credentialVault.isConfigured()) {
            throw new McpCredentialConfigurationException("后端尚未配置 MCP 凭据主密钥，请按页面指引完成配置。");
        }

        McpConnection connection = new McpConnection();
        connection.setOwnerUserId(userId);
        connection.setName(normalizedName);
        connection.setServerUrl(endpoint.normalizedUrl());
        connection.setAuthType(normalizedAuth);
        connection.setEnabled(true);
        connection.setStatus(McpConnectionStatus.DISCONNECTED);
        connection = connectionRepository.save(connection);
        if (normalizedAuth == McpAuthType.BEARER_TOKEN) {
            try {
                credentialVault.storeBearerToken(userId, connection.getId(), bearerToken);
            } catch (RuntimeException ex) {
                connectionRepository.delete(connection);
                throw ex;
            }
        }
        runtimeManager.connectAndDiscover(userId, connection.getId());
        return owned(userId, connection.getId());
    }

    public McpConnection reconnect(Long userId, Long connectionId) {
        runtimeManager.connectAndDiscover(userId, connectionId);
        return owned(userId, connectionId);
    }

    public McpConnection replaceBearerToken(Long userId, Long connectionId, String bearerToken) {
        McpConnection connection = owned(userId, connectionId);
        if (!credentialVault.isConfigured()) {
            throw new McpCredentialConfigurationException("后端尚未配置 MCP 凭据主密钥，请按页面指引完成配置。");
        }
        credentialVault.storeBearerToken(userId, connectionId, bearerToken);
        connection.setAuthType(McpAuthType.BEARER_TOKEN);
        connection.setEnabled(true);
        connection.setStatus(McpConnectionStatus.DISCONNECTED);
        connectionRepository.save(connection);
        runtimeManager.connectAndDiscover(userId, connectionId);
        return owned(userId, connectionId);
    }

    public McpConnection setEnabled(Long userId, Long connectionId, boolean enabled) {
        McpConnection connection = owned(userId, connectionId);
        connection.setEnabled(enabled);
        if (!enabled) {
            runtimeManager.disconnect(userId, connectionId);
            connection.setStatus(McpConnectionStatus.DISABLED);
            connection.setLastError(null);
            return connectionRepository.save(connection);
        }
        connection.setStatus(McpConnectionStatus.DISCONNECTED);
        connectionRepository.save(connection);
        runtimeManager.connectAndDiscover(userId, connectionId);
        return owned(userId, connectionId);
    }

    @Transactional
    public McpDiscoveredTool setToolEnabled(Long userId, Long toolId, boolean enabled) {
        requireUser(userId);
        McpDiscoveredTool tool = toolRepository.findById(toolId)
                .orElseThrow(() -> new IllegalArgumentException("MCP 工具不存在。"));
        owned(userId, tool.getConnectionId());
        tool.setEnabled(enabled);
        return toolRepository.save(tool);
    }

    @Transactional
    public void delete(Long userId, Long connectionId) {
        McpConnection connection = owned(userId, connectionId);
        runtimeManager.disconnect(userId, connectionId);
        toolRepository.deleteByConnectionId(connectionId);
        credentialVault.delete(userId, connectionId);
        connectionRepository.delete(connection);
    }

    @Transactional(readOnly = true)
    public List<ConnectionView> list(Long userId) {
        requireUser(userId);
        return connectionRepository.findByOwnerUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(connection -> new ConnectionView(
                        connection,
                        toolRepository.findByConnectionIdOrderByExposedNameAsc(connection.getId()),
                        credentialVault.hasCredential(userId, connection.getId())))
                .toList();
    }

    public boolean credentialVaultConfigured() {
        return credentialVault.isConfigured();
    }

    public String credentialVaultBackend() {
        return credentialVault.backendName();
    }

    public boolean allowPrivateNetworks() {
        return urlPolicy.isAllowPrivateNetworks();
    }

    public boolean allowInsecureHttp() {
        return urlPolicy.isAllowInsecureHttp();
    }

    private McpConnection owned(Long userId, Long connectionId) {
        requireUser(userId);
        return connectionRepository.findByIdAndOwnerUserId(connectionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("MCP 连接不存在或不属于当前用户。"));
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("请先登录。");
        }
    }

    private String validateName(String name) {
        String value = name == null ? "" : name.strip();
        if (!CONNECTION_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("连接名称需为 2-64 个中文、字母、数字、空格、点、下划线或连字符。");
        }
        return value;
    }

    public record ConnectionView(McpConnection connection, List<McpDiscoveredTool> tools,
                                 boolean credentialConfigured) {
    }
}
