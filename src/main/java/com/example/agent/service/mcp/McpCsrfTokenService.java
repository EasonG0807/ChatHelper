package com.example.agent.service.mcp;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class McpCsrfTokenService {

    private static final String SESSION_KEY = "mcpCsrfToken";
    private final SecureRandom secureRandom = new SecureRandom();

    public String token(HttpSession session) {
        Object existing = session.getAttribute(SESSION_KEY);
        if (existing instanceof String value && !value.isBlank()) {
            return value;
        }
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(SESSION_KEY, token);
        return token;
    }

    public void verify(HttpSession session, String submitted) {
        String expected = token(session);
        if (submitted == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), submitted.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("页面安全令牌已失效，请刷新页面后重试。");
        }
    }
}
