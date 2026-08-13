package com.example.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_run_event",
        indexes = @Index(name = "idx_agent_run_event_cursor", columnList = "run_id,event_sequence"),
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_run_event_sequence",
                columnNames = {"run_id", "event_sequence"}))
@Data
public class AgentRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "event_sequence", nullable = false)
    private Long sequence;

    @Column(name = "event_data", nullable = false, columnDefinition = "TEXT")
    private String data;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
