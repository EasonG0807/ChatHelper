package com.example.agent.tool.react;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ResourceDownloadTool implements ReactTool {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "txt", "md", "html", "json", "csv", "png", "jpg", "jpeg", "gif", "webp");

    private final WebClient webClient;
    private final AgentWorkspaceService workspaceService;
    private final int maxBytes;

    public ResourceDownloadTool(WebClient webClient,
                                AgentWorkspaceService workspaceService,
                                @Value("${agent.react.resource-download.max-bytes:10485760}") int maxBytes) {
        this.webClient = webClient;
        this.workspaceService = workspaceService;
        this.maxBytes = maxBytes;
    }

    @Override
    public String name() {
        return "resource_download";
    }

    @Override
    public String description() {
        return "Download a public resource into the current agent workspace. Allowed file types: pdf, txt, md, html, json, csv, images.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "url": "string, required, public http/https URL",
                  "fileName": "string, required"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String url = ToolArguments.string(arguments, "url");
        String fileName = ToolArguments.string(arguments, "fileName");
        try {
            URI uri = UrlSafety.requirePublicHttpUrl(url);
            if (!isAllowed(fileName)) {
                return ToolExecutionResult.failure("File extension is not allowed: " + fileName);
            }
            byte[] bytes = webClient.get()
                    .uri(uri)
                    .header("User-Agent", "RagAgent/1.0")
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(30));
            if (bytes == null || bytes.length == 0) {
                return ToolExecutionResult.failure("The resource returned no bytes.");
            }
            if (bytes.length > maxBytes) {
                return ToolExecutionResult.failure("The resource is too large. maxBytes=" + maxBytes + ", actual=" + bytes.length);
            }

            Path workspace = context.workspaceRoot();
            Path target = workspaceService.createArtifactPath(workspace, fileName);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return ToolExecutionResult.success(
                    "Downloaded " + bytes.length + " bytes to " + target.getFileName(), target.toString());
        } catch (Exception ex) {
            return ToolExecutionResult.failure("Resource download failed: " + ex.getMessage());
        }
    }

    private boolean isAllowed(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.contains(ext);
    }
}
