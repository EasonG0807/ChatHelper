package com.example.agent.tool.react;

import com.example.agent.entity.AgentToolSource;
import com.example.agent.entity.McpDiscoveredTool;
import com.example.agent.service.mcp.McpConnectionRuntimeManager;

import java.util.Map;

public class McpDiscoveredReactTool implements ReactTool {

    private final McpDiscoveredTool tool;
    private final McpConnectionRuntimeManager runtimeManager;

    public McpDiscoveredReactTool(McpDiscoveredTool tool, McpConnectionRuntimeManager runtimeManager) {
        this.tool = tool;
        this.runtimeManager = runtimeManager;
    }

    @Override
    public String name() {
        return tool.getExposedName();
    }

    @Override
    public String description() {
        return tool.getDescription() == null ? "" : tool.getDescription();
    }

    @Override
    public String parameters() {
        return tool.getInputSchema() == null ? "{}" : tool.getInputSchema();
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        if (context == null || context.userId() == null) {
            return ToolExecutionResult.failure("MCP 工具缺少用户执行上下文。");
        }
        return runtimeManager.execute(context.userId(), tool.getConnectionId(), tool.getRemoteName(), context, arguments);
    }

    @Override
    public AgentToolSource source() {
        return AgentToolSource.MCP;
    }
}
