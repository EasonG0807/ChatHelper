package com.example.agent.repository;

import com.example.agent.entity.AgentArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentArtifactRepository extends JpaRepository<AgentArtifact, Long> {

    List<AgentArtifact> findByUserIdAndSessionIdOrderByCreatedAtDescIdDesc(Long userId, Long sessionId);

    Optional<AgentArtifact> findByIdAndUserId(Long id, Long userId);

    Optional<AgentArtifact> findFirstByUserIdAndSessionIdAndMessageIdAndToolNameAndRelativePath(
            Long userId, Long sessionId, Long messageId, String toolName, String relativePath);

    void deleteBySessionId(Long sessionId);
}
