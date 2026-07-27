package com.example.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Structured long-term memory extracted from an agent conversation.
 *
 * A memory is deliberately separate from the raw transcript: it can be
 * ranked, expired, merged and inspected without replaying the whole session.
 */
@Entity
@Table(name = "agent_memory")
@Data
public class AgentMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long sessionId;

    private Long sourceMessageId;

    @Column(nullable = false)
    private String memoryType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private String memoryKey;

    private Integer importance = 50;

    private Double confidence = 0.5;

    private Boolean active = true;

    private LocalDateTime lastAccessedAt;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
