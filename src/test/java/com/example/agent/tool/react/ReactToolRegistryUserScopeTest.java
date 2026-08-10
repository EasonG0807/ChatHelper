package com.example.agent.tool.react;

import com.example.agent.entity.AgentToolSource;
import com.example.agent.repository.AgentToolConfigRepository;
import com.example.agent.service.mcp.UserMcpToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReactToolRegistryUserScopeTest {

    @Test
    @SuppressWarnings("unchecked")
    void combinesSharedToolsOnlyWithTheCurrentUsersPrivateMcpTools() {
        ReactTool shared = tool("date_time", AgentToolSource.LOCAL);
        ReactTool privateMcp = tool("mcp_c7_search_abcd1234", AgentToolSource.MCP);
        ToolCallbackProvider localProvider = mock(ToolCallbackProvider.class);
        when(localProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        AgentToolConfigRepository configRepository = mock(AgentToolConfigRepository.class);
        when(configRepository.findByToolName(anyString())).thenReturn(Optional.empty());
        UserMcpToolCatalog userCatalog = mock(UserMcpToolCatalog.class);
        when(userCatalog.listEnabledTools(1L)).thenReturn(List.of(privateMcp));
        when(userCatalog.listEnabledTools(2L)).thenReturn(List.of());

        ReactToolRegistry registry = new ReactToolRegistry(
                List.of(shared), localProvider, providers, configRepository, userCatalog, new ObjectMapper());

        assertEquals(List.of("date_time", "mcp_c7_search_abcd1234"),
                registry.list(1L).stream().map(ReactTool::name).toList());
        assertEquals(List.of("date_time"), registry.list(2L).stream().map(ReactTool::name).toList());
        assertTrue(registry.find(2L, "mcp_c7_search_abcd1234").isEmpty());
    }

    private ReactTool tool(String name, AgentToolSource source) {
        return new ReactTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public String parameters() { return "{}"; }
            @Override public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
                return ToolExecutionResult.success("ok");
            }
            @Override public AgentToolSource source() { return source; }
        };
    }
}
