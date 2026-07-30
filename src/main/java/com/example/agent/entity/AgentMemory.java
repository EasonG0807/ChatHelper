package com.example.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Structured long-term memory extracted from an agent conversation.
 *
 * A memory is deliberately separate from the raw transcript: it can be
 * ranked, expired, merged and inspected without replaying the whole session.
 */
@Entity
@Table(name = "agent_memory", indexes = {
        @Index(name = "idx_agent_memory_user_status", columnList = "user_id, status"),
        @Index(name = "idx_agent_memory_identity", columnList = "user_id, scope_key, memory_key"),
        @Index(name = "idx_agent_memory_source_message", columnList = "source_message_id")
})
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

    /** Stable backend-generated identity: canonical subject::predicate. */
    @Column(length = 180)
    private String memoryKey;

    @Column(length = 120)
    private String subjectKey;

    @Column(length = 120)
    private String predicateKey;

    @Column(columnDefinition = "TEXT")
    private String factValue;

    @Column(length = 64)
    private String valueFingerprint;

    /** USER or SESSION:{id}; unlike sessionId this value is never null. */
    @Column(length = 80)
    private String scopeKey;

    /**
     * Unique only for the current ACTIVE version. Historical/conflicting rows
     * keep this null, so PostgreSQL can retain every old version.
     */
    @Column(length = 64, unique = true)
    private String currentKey;

    private Integer version = 1;

    private Long supersedesId;

    private Long replacedById;

    private Long conflictWithId;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private AgentMemoryStatus status = AgentMemoryStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private AgentMemoryVerificationStatus verificationStatus = AgentMemoryVerificationStatus.UNVERIFIED;

    @Column(length = 24)
    private String sourceType;

    private Integer importance = 50;

    private Double confidence = 0.5;

    private Boolean active = true;

    private LocalDateTime lastAccessedAt;

    private LocalDateTime lastVerifiedAt;

    private LocalDateTime verificationDueAt;

    private LocalDateTime validFrom = LocalDateTime.now();

    private LocalDateTime validTo;

    private LocalDateTime invalidatedAt;

    @Column(columnDefinition = "TEXT")
    private String invalidationReason;

    private LocalDateTime expiresAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
