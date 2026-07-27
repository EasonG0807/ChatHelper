package com.example.agent.tool.react;

import java.nio.file.Path;

public record ToolExecutionContext(
        Long userId,
        Long sessionId,
        Long messageId,
        Path workspaceRoot
) {
}
