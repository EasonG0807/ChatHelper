package com.example.agent.service.mcp;

import com.example.agent.entity.McpCredential;
import com.example.agent.repository.McpCredentialRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Primary
@ConditionalOnProperty(name = "agent.mcp.credentials.backend", havingValue = "database", matchIfMissing = true)
public class EncryptedDatabaseMcpCredentialVault implements McpCredentialVault {

    private static final String CREDENTIAL_TYPE = "BEARER_TOKEN";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final McpCredentialRepository credentialRepository;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String activeKeyId;

    public EncryptedDatabaseMcpCredentialVault(McpCredentialRepository credentialRepository,
                                               Environment environment,
                                               @Value("${agent.mcp.credentials.active-key-id:v1}") String activeKeyId) {
        this.credentialRepository = credentialRepository;
        this.environment = environment;
        this.activeKeyId = validateKeyId(activeKeyId);
    }

    @Override
    @Transactional
    public void storeBearerToken(Long userId, Long connectionId, String plaintextToken) {
        requireIdentity(userId, connectionId);
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new IllegalArgumentException("MCP Token 不能为空。");
        }
        if (plaintextToken.length() > 16000) {
            throw new IllegalArgumentException("MCP Token 长度超过限制。");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, activeKeyId, nonce,
                aad(userId, connectionId), plaintextToken.getBytes(StandardCharsets.UTF_8));

        McpCredential credential = credentialRepository
                .findByConnectionIdAndOwnerUserId(connectionId, userId)
                .orElseGet(McpCredential::new);
        credential.setOwnerUserId(userId);
        credential.setConnectionId(connectionId);
        credential.setCredentialType(CREDENTIAL_TYPE);
        credential.setKeyId(activeKeyId);
        credential.setNonce(Base64.getEncoder().encodeToString(nonce));
        credential.setCiphertext(Base64.getEncoder().encodeToString(encrypted));
        credentialRepository.save(credential);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> readBearerToken(Long userId, Long connectionId) {
        requireIdentity(userId, connectionId);
        return credentialRepository.findByConnectionIdAndOwnerUserId(connectionId, userId)
                .map(credential -> decrypt(userId, connectionId, credential));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCredential(Long userId, Long connectionId) {
        requireIdentity(userId, connectionId);
        return credentialRepository.existsByConnectionIdAndOwnerUserId(connectionId, userId);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long connectionId) {
        requireIdentity(userId, connectionId);
        credentialRepository.deleteByConnectionIdAndOwnerUserId(connectionId, userId);
    }

    @Override
    public boolean isConfigured() {
        try {
            resolveKey(activeKeyId);
            return true;
        } catch (McpCredentialConfigurationException ex) {
            return false;
        }
    }

    @Override
    public String backendName() {
        return "AES-256-GCM / PostgreSQL";
    }

    private String decrypt(Long userId, Long connectionId, McpCredential credential) {
        try {
            byte[] nonce = Base64.getDecoder().decode(credential.getNonce());
            byte[] ciphertext = Base64.getDecoder().decode(credential.getCiphertext());
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, validateKeyId(credential.getKeyId()), nonce,
                    aad(userId, connectionId), ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new McpCredentialConfigurationException("MCP 凭据密文格式无效。", ex);
        }
    }

    private byte[] crypt(int mode, String keyId, byte[] nonce, byte[] aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(resolveKey(keyId), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (AEADBadTagException ex) {
            throw new McpCredentialConfigurationException("MCP 凭据无法解密，主密钥不匹配或数据已被修改。", ex);
        } catch (GeneralSecurityException ex) {
            throw new McpCredentialConfigurationException("MCP 凭据加密组件初始化失败。", ex);
        }
    }

    private byte[] resolveKey(String keyId) {
        String normalized = validateKeyId(keyId);
        String configured = environment.getProperty("MCP_CREDENTIAL_KEY_"
                + normalized.toUpperCase(Locale.ROOT).replace('-', '_'));
        if (configured == null || configured.isBlank()) {
            configured = environment.getProperty("agent.mcp.credentials.keys." + normalized);
        }
        if (configured == null || configured.isBlank()) {
            throw new McpCredentialConfigurationException(
                    "后端尚未配置 MCP_CREDENTIAL_KEY_" + normalized.toUpperCase(Locale.ROOT) + "。");
        }
        try {
            byte[] key = Base64.getDecoder().decode(configured.strip());
            if (key.length != 32) {
                throw new IllegalArgumentException("expected 32 bytes");
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new McpCredentialConfigurationException(
                    "MCP_CREDENTIAL_KEY_" + normalized.toUpperCase(Locale.ROOT)
                            + " 必须是 Base64 编码的 32 字节随机密钥。", ex);
        }
    }

    private byte[] aad(Long userId, Long connectionId) {
        return (userId + "|" + connectionId + "|" + CREDENTIAL_TYPE).getBytes(StandardCharsets.UTF_8);
    }

    private String validateKeyId(String keyId) {
        String value = keyId == null ? "" : keyId.strip();
        if (!KEY_ID.matcher(value).matches()) {
            throw new McpCredentialConfigurationException("MCP 凭据 key id 格式无效。");
        }
        return value;
    }

    private void requireIdentity(Long userId, Long connectionId) {
        if (userId == null || connectionId == null) {
            throw new IllegalArgumentException("用户和 MCP 连接不能为空。");
        }
    }
}
