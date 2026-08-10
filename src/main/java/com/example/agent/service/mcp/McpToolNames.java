package com.example.agent.service.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

final class McpToolNames {

    private McpToolNames() {
    }

    static String exposedName(Long connectionId, String remoteName) {
        String normalized = Normalizer.normalize(remoteName == null ? "tool" : remoteName,
                        Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[_-]+|[_-]+$", "");
        if (normalized.isBlank()) {
            normalized = "tool";
        }
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80);
        }
        return "mcp_c" + connectionId + "_" + normalized + "_" + shortHash(remoteName);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }
}
