package com.example.agent.tool.react;

import java.nio.file.Path;

public record ToolExecutionResult(
        boolean success,
        String content,
        String artifactPath,
        String errorMessage
) {

    private static final int MAX_CONTENT_LENGTH = 12000;

    public static ToolExecutionResult success(String content) {
        return new ToolExecutionResult(true, truncate(content), null, null);
    }

    public static ToolExecutionResult success(String content, String artifactPath) {
        return new ToolExecutionResult(true, truncate(content), artifactPath, null);
    }

    public static ToolExecutionResult failure(String errorMessage) {
        return new ToolExecutionResult(false, null, null, truncate(errorMessage));
    }

    public String toObservation() {
        if (success) {
            StringBuilder observation = new StringBuilder();
            observation.append(content == null ? "" : content);
            if (artifactPath != null && !artifactPath.isBlank()) {
                observation.append("\n\nArtifact ready: ").append(artifactFileName());
            }
            return observation.toString();
        }
        return "Tool failed: " + (errorMessage == null ? "Unknown error." : errorMessage);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_CONTENT_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_CONTENT_LENGTH) + "\n\n[TRUNCATED]";
    }

    private String artifactFileName() {
        try {
            Path fileName = Path.of(artifactPath).getFileName();
            return fileName == null ? "generated file" : fileName.toString();
        } catch (RuntimeException invalidPath) {
            return "generated file";
        }
    }
}
