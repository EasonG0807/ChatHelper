package com.example.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Metadata for a file produced by an Agent tool.
 *
 * <p>The stored path is always relative to the owning session workspace. An
 * absolute server path must never be persisted or returned to the browser.</p>
 */
@Entity
@Table(name = "agent_artifact", indexes = {
        @Index(name = "idx_agent_artifact_user_session", columnList = "user_id, session_id"),
        @Index(name = "idx_agent_artifact_message", columnList = "message_id")
})
@Data
public class AgentArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "tool_name", length = 120)
    private String toolName;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
