package com.example.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "mcp_connection",
        uniqueConstraints = @UniqueConstraint(name = "uk_mcp_connection_owner_name",
                columnNames = {"owner_user_id", "name"}),
        indexes = @Index(name = "idx_mcp_connection_owner", columnList = "owner_user_id"))
@Getter
@Setter
public class McpConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 64)
    private String name;

    /** Full URL of the remote MCP endpoint. */
    @Column(name = "server_url", nullable = false, length = 1024)
    private String serverUrl;

    /** Last transport successfully selected by automatic detection. */
    @Enumerated(EnumType.STRING)
    @Column(name = "transport_type", length = 32)
    private McpTransportType transportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 32)
    private McpAuthType authType = McpAuthType.NONE;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private McpConnectionStatus status = McpConnectionStatus.DISCONNECTED;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_connected_at")
    private LocalDateTime lastConnectedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Version
    private Long version;

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
