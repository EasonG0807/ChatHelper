package com.example.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One skill document in SKILL.md style: lightweight metadata (name + description)
 * that is always visible to the planner, plus a full markdown body that is only
 * loaded into context when the agent invokes the skill.
 *
 * ownerUserId == null means a shared built-in skill seeded from classpath
 * resources; otherwise the skill belongs to a single user.
 */
@Entity
@Table(name = "agent_skill_doc",
        uniqueConstraints = @UniqueConstraint(columnNames = {"name", "owner_user_id"}))
@Data
public class AgentSkillDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    private Boolean builtIn = false;

    private Boolean enabled = true;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isOwnedBy(Long userId) {
        return ownerUserId != null && ownerUserId.equals(userId);
    }

    public boolean isBuiltInSkill() {
        return Boolean.TRUE.equals(builtIn);
    }
}
