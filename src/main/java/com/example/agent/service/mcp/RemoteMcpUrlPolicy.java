package com.example.agent.service.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public class RemoteMcpUrlPolicy {

    private static final Set<String> SENSITIVE_QUERY_KEYS = Set.of(
            "token", "access_token", "api_key", "apikey", "authorization", "secret", "key");

    private final boolean allowPrivateNetworks;
    private final boolean allowInsecureHttp;

    public RemoteMcpUrlPolicy(
            @Value("${agent.mcp.remote.allow-private-networks:false}") boolean allowPrivateNetworks,
            @Value("${agent.mcp.remote.allow-insecure-http:false}") boolean allowInsecureHttp) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.allowInsecureHttp = allowInsecureHttp;
    }

    public ValidatedEndpoint validate(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("MCP Endpoint 地址不能为空。");
        }
        final URI uri;
        try {
            uri = URI.create(serverUrl.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("MCP Endpoint 地址格式无效。", ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowInsecureHttp && "http".equals(scheme))) {
            throw new IllegalArgumentException(allowInsecureHttp
                    ? "MCP 地址只允许使用 HTTPS 或已明确放行的 HTTP。"
                    : "MCP 地址必须使用 HTTPS；本地开发需显式开启 MCP_ALLOW_INSECURE_HTTP。" );
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || !uri.isAbsolute()) {
            throw new IllegalArgumentException("MCP 地址必须包含有效的主机名。");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("MCP 地址不能包含用户信息或 URL fragment。");
        }
        rejectSecretsInQuery(uri.getRawQuery());
        validateHost(uri.getHost());

        String baseUri = scheme + "://" + uri.getRawAuthority();
        String endpoint = uri.getRawPath();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "/mcp";
        }
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            endpoint += "?" + uri.getRawQuery();
        }
        String normalizedUrl = uri.getRawPath() == null || uri.getRawPath().isBlank()
                ? baseUri + endpoint : uri.toString();
        return new ValidatedEndpoint(normalizedUrl, baseUri, endpoint);
    }

    public boolean isAllowPrivateNetworks() {
        return allowPrivateNetworks;
    }

    public boolean isAllowInsecureHttp() {
        return allowInsecureHttp;
    }

    private void validateHost(String host) {
        if (allowPrivateNetworks) {
            return;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost")) {
            throw new IllegalArgumentException("安全策略不允许 MCP 访问本机地址。");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateOrSpecial(address)) {
                    throw new IllegalArgumentException("安全策略不允许 MCP 访问内网或特殊网络地址。");
                }
            }
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("无法解析 MCP 服务器主机名。", ex);
        }
    }

    private void rejectSecretsInQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return;
        }
        boolean sensitive = Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2)[0])
                .map(value -> URLDecoder.decode(value, StandardCharsets.UTF_8))
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(SENSITIVE_QUERY_KEYS::contains);
        if (sensitive) {
            throw new IllegalArgumentException("MCP Token 不能放在 URL 查询参数中，请使用 Bearer Token 字段。");
        }
    }

    private boolean isPrivateOrSpecial(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
            return true; // IPv6 unique-local fc00::/7
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127);
        }
        return false;
    }

    public record ValidatedEndpoint(String normalizedUrl, String baseUri, String endpoint) {
    }
}
