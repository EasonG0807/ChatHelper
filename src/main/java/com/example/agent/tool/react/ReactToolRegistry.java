package com.example.agent.tool.react;

import com.example.agent.entity.AgentToolConfig;
import com.example.agent.entity.AgentToolSource;
import com.example.agent.repository.AgentToolConfigRepository;
import com.example.agent.service.mcp.UserMcpToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ReactToolRegistry {

    private final Map<String, ReactTool> sharedTools;
    private final AgentToolConfigRepository toolConfigRepository;
    private final UserMcpToolCatalog userMcpToolCatalog;

    public ReactToolRegistry(List<ReactTool> reactTools,
                             @Qualifier("agentLocalToolCallbackProvider") ToolCallbackProvider localToolCallbackProvider,
                             ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                             AgentToolConfigRepository toolConfigRepository,
                             UserMcpToolCatalog userMcpToolCatalog,
                             ObjectMapper objectMapper) {
        this.toolConfigRepository = toolConfigRepository;
        this.userMcpToolCatalog = userMcpToolCatalog;
        Map<String, ReactTool> discovered = new LinkedHashMap<>();
        for (ReactTool tool : reactTools) {
            discovered.putIfAbsent(tool.name(), tool);
        }

        Set<String> localToolNames = Arrays.stream(localToolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        toolCallbackProviders.forEach(provider -> {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                AgentToolSource source = localToolNames.contains(callback.getToolDefinition().name())
                        ? AgentToolSource.LOCAL
                        : AgentToolSource.MCP;
                discovered.putIfAbsent(callback.getToolDefinition().name(),
                        new ToolCallbackReactTool(callback, source, objectMapper));
            }
        });
        this.sharedTools = Map.copyOf(discovered);
    }

    public Optional<ReactTool> find(String name) {
        return find(null, name);
    }

    public Optional<ReactTool> find(Long userId, String name) {
        ReactTool shared = sharedTools.get(name);
        if (shared != null) {
            return isEnabled(name) ? Optional.of(shared) : Optional.empty();
        }
        return userMcpToolCatalog.listEnabledTools(userId).stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst();
    }

    public List<ReactTool> list() {
        return sharedTools.values().stream()
                .filter(tool -> isEnabled(tool.name()))
                .toList();
    }

    public List<ReactTool> list(Long userId) {
        Map<String, ReactTool> visible = new LinkedHashMap<>();
        list().forEach(tool -> visible.put(tool.name(), tool));
        userMcpToolCatalog.listEnabledTools(userId).forEach(tool -> visible.putIfAbsent(tool.name(), tool));
        return List.copyOf(visible.values());
    }

    public String toolDescriptions() {
        return toolDescriptions(null);
    }

    public String toolDescriptions(Long userId) {
        return list(userId).stream()
                .map(tool -> """
                        - name: %s
                          source: %s
                          description: %s
                          parameters: %s
                        """.formatted(tool.name(), tool.source(), tool.description(), tool.parameters()))
                .collect(Collectors.joining("\n"));
    }

    private boolean isEnabled(String toolName) {
        return toolConfigRepository.findByToolName(toolName)
                .map(AgentToolConfig::getEnabled)
                .orElse(true);
    }
}
