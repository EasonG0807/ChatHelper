package com.example.agent.tool.react;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ToolListReactTool implements ReactTool {

    private final ReactToolRegistry toolRegistry;

    public ToolListReactTool(@Lazy ReactToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String name() {
        return "tool_list";
    }

    @Override
    public String description() {
        return "List all tools currently available to this agent and explain what each tool can do.";
    }

    @Override
    public String parameters() {
        return "{}";
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String content = toolRegistry.list().stream()
                .filter(tool -> !"terminate".equals(tool.name()))
                .map(tool -> "- `" + tool.name() + "`: " + tool.description())
                .collect(Collectors.joining("\n"));
        return ToolExecutionResult.success(content.isBlank() ? "No tools are registered." : content);
    }
}
