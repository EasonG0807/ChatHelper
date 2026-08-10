package com.example.agent.controller;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAdminTemplateTest {

    @Test
    void rendersEmptyToolCenterAndCredentialGuideWithoutExternalServices() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        Map<String, Object> variables = new HashMap<>();
        variables.put("toolCount", 0L);
        variables.put("localToolCount", 0L);
        variables.put("mcpToolCount", 0L);
        variables.put("systemToolCount", 0L);
        variables.put("enabledToolCount", 0L);
        variables.put("tools", List.of());
        variables.put("localTools", List.of());
        variables.put("mcpTools", List.of());
        variables.put("systemTools", List.of());
        variables.put("mcpConnections", List.of());
        variables.put("isAdmin", true);
        variables.put("credentialVaultConfigured", false);
        variables.put("credentialVaultBackend", "AES-256-GCM / PostgreSQL");
        variables.put("allowPrivateNetworks", false);
        variables.put("allowInsecureHttp", false);
        variables.put("mcpCsrfToken", "test-csrf-token");
        Context context = new Context(Locale.SIMPLIFIED_CHINESE, variables);

        String html = engine.process("agent-admin", context);

        assertTrue(html.contains("连接我的 MCP Server"));
        assertTrue(html.contains("MCP Endpoint URL"));
        assertTrue(html.contains("自动检测 Streamable HTTP 与旧版 SSE"));
        assertTrue(html.contains("credentialGuideDialog"));
        assertTrue(html.contains("MCP_CREDENTIAL_KEY_V1"));
        assertTrue(html.contains("data-auto-open=\"true\""));
    }
}
