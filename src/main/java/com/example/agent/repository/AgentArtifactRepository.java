package com.example.agent.repository;

import com.example.agent.entity.AgentArtifact;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentArtifactRepository extends JpaRepository<AgentArtifact, Long> {

    List<AgentArtifact> findByUserIdAndSessionIdOrderByCreatedAtDescIdDesc(Long userId, Long sessionId);

    Optional<AgentArtifact> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select artifact from AgentArtifact artifact "
            + "where artifact.id = :artifactId and artifact.userId = :userId")
    Optional<AgentArtifact> findOwnedForUpdate(@Param("artifactId") Long artifactId,
                                                @Param("userId") Long userId);

    Optional<AgentArtifact> findFirstByUserIdAndSessionIdAndMessageIdAndToolNameAndRelativePath(
            Long userId, Long sessionId, Long messageId, String toolName, String relativePath);

    boolean existsByUserIdAndSessionIdAndRelativePathAndIdNot(
            Long userId, Long sessionId, String relativePath, Long id);

    void deleteBySessionId(Long sessionId);
}
