package com.example.agent.controller;

import com.example.agent.entity.McpAuthType;
import com.example.agent.service.mcp.McpConnectionService;
import com.example.agent.service.mcp.McpCsrfTokenService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/agent/mcp")
@RequiredArgsConstructor
public class McpConnectionController {

    private final McpConnectionService connectionService;
    private final McpCsrfTokenService csrfTokenService;

    @PostMapping("/connections")
    public String create(@RequestParam String name,
                         @RequestParam String serverUrl,
                         @RequestParam McpAuthType authType,
                         @RequestParam(required = false) String bearerToken,
                         @RequestParam String csrfToken,
                         HttpSession session,
                         RedirectAttributes redirectAttributes) {
        Long userId = userId(session);
        if (userId == null) {
            return "redirect:/auth/login";
        }
        try {
            csrfTokenService.verify(session, csrfToken);
            connectionService.create(userId, name, serverUrl, authType, bearerToken);
            redirectAttributes.addFlashAttribute("mcpMessage", "MCP 连接成功，工具已完成发现。");
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("mcpError", safeMessage(ex));
        }
        return "redirect:/agent/admin";
    }

    @PostMapping("/connections/{connectionId}/reconnect")
    public String reconnect(@PathVariable Long connectionId,
                            @RequestParam String csrfToken,
                            HttpSession session,
                            RedirectAttributes redirectAttributes) {
        return run(session, csrfToken, redirectAttributes,
                () -> connectionService.reconnect(userId(session), connectionId),
                "连接成功，工具目录已刷新。");
    }

    @PostMapping("/connections/{connectionId}/credential")
    public String replaceCredential(@PathVariable Long connectionId,
                                    @RequestParam String bearerToken,
                                    @RequestParam String csrfToken,
                                    HttpSession session,
                                    RedirectAttributes redirectAttributes) {
        return run(session, csrfToken, redirectAttributes,
                () -> connectionService.replaceBearerToken(userId(session), connectionId, bearerToken),
                "Token 已替换并重新连接。");
    }

    @PostMapping("/connections/{connectionId}/enabled")
    public String setConnectionEnabled(@PathVariable Long connectionId,
                                       @RequestParam boolean enabled,
                                       @RequestParam String csrfToken,
                                       HttpSession session,
                                       RedirectAttributes redirectAttributes) {
        return run(session, csrfToken, redirectAttributes,
                () -> connectionService.setEnabled(userId(session), connectionId, enabled),
                enabled ? "MCP 连接已启用。" : "MCP 连接已停用。");
    }

    @PostMapping("/connections/{connectionId}/delete")
    public String delete(@PathVariable Long connectionId,
                         @RequestParam String csrfToken,
                         HttpSession session,
                         RedirectAttributes redirectAttributes) {
        return run(session, csrfToken, redirectAttributes,
                () -> connectionService.delete(userId(session), connectionId),
                "MCP 连接、发现的工具和加密凭据已删除。");
    }

    @PostMapping("/tools/{toolId}/enabled")
    public String setToolEnabled(@PathVariable Long toolId,
                                 @RequestParam boolean enabled,
                                 @RequestParam String csrfToken,
                                 HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        return run(session, csrfToken, redirectAttributes,
                () -> connectionService.setToolEnabled(userId(session), toolId, enabled),
                enabled ? "MCP 工具已启用。" : "MCP 工具已停用。");
    }

    private String run(HttpSession session,
                       String csrfToken,
                       RedirectAttributes redirectAttributes,
                       Runnable action,
                       String successMessage) {
        if (userId(session) == null) {
            return "redirect:/auth/login";
        }
        try {
            csrfTokenService.verify(session, csrfToken);
            action.run();
            redirectAttributes.addFlashAttribute("mcpMessage", successMessage);
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("mcpError", safeMessage(ex));
        }
        return "redirect:/agent/admin";
    }

    private Long userId(HttpSession session) {
        return (Long) session.getAttribute("uid");
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "MCP 操作失败，请稍后重试。";
        }
        message = message.replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", "$1***")
                .replaceAll("(?i)(token\\s*[:=]\\s*)[^\\s,;]+", "$1***")
                .replaceAll("[\\r\\n\\t]+", " ")
                .strip();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
