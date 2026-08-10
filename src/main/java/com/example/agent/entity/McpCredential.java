package com.example.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Encrypted credential payload for the database-backed vault implementation.
 * The entity deliberately avoids Lombok's generated toString so ciphertext is
 * not accidentally copied into application logs.
 */
@Entity
@Table(name = "mcp_credential",
        uniqueConstraints = @UniqueConstraint(name = "uk_mcp_credential_connection",
                columnNames = "connection_id"),
        indexes = @Index(name = "idx_mcp_credential_owner", columnList = "owner_user_id"))
@Getter
@Setter
public class McpCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "credential_type", nullable = false, length = 32)
    private String credentialType;

    @Column(name = "ciphertext", nullable = false, columnDefinition = "TEXT")
    private String ciphertext;

    @Column(name = "nonce", nullable = false, length = 64)
    private String nonce;

    @Column(name = "key_id", nullable = false, length = 32)
    private String keyId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
