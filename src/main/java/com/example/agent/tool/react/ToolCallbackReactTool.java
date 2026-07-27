package com.example.agent.tool.react;

import com.example.agent.entity.AgentToolSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.LinkedHashMap;
import java.util.Map;

public class ToolCallbackReactTool implements ReactTool {

    private final ToolCallback callback;
    private final AgentToolSource source;
    private final ObjectMapper objectMapper;

    public ToolCallbackReactTool(ToolCallback callback, AgentToolSource source, ObjectMapper objectMapper) {
        this.callback = callback;
        this.source = source;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return callback.getToolDefinition().name();
    }

    @Override
    public String description() {
        return callback.getToolDefinition().description();
    }

    @Override
    public String parameters() {
        return callback.getToolDefinition().inputSchema();
    }

    @Override
    public AgentToolSource source() {
        return source;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        try {
            String input = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
            String output = callback.call(input, new ToolContext(toolContext(context)));
            return ToolExecutionResult.success(output);
        } catch (JsonProcessingException ex) {
            return ToolExecutionResult.failure("Tool input serialization failed: " + ex.getMessage());
        } catch (RuntimeException ex) {
            return ToolExecutionResult.failure("Tool callback failed: " + ex.getMessage());
        }
    }

    private Map<String, Object> toolContext(ToolExecutionContext context) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (context == null) {
            return values;
        }
        values.put("userId", context.userId());
        values.put("sessionId", context.sessionId());
        values.put("messageId", context.messageId());
        values.put("workspaceRoot", context.workspaceRoot() == null ? null : context.workspaceRoot().toString());
        return values;
    }
}
