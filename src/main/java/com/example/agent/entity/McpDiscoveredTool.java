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

@Entity
@Table(name = "mcp_discovered_tool",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_mcp_tool_connection_remote",
                        columnNames = {"connection_id", "remote_name"}),
                @UniqueConstraint(name = "uk_mcp_tool_connection_exposed",
                        columnNames = {"connection_id", "exposed_name"})
        },
        indexes = @Index(name = "idx_mcp_tool_connection", columnList = "connection_id"))
@Getter
@Setter
public class McpDiscoveredTool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "remote_name", nullable = false, length = 160)
    private String remoteName;

    @Column(name = "exposed_name", nullable = false, length = 192)
    private String exposedName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "input_schema", nullable = false, columnDefinition = "TEXT")
    private String inputSchema = "{}";

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
