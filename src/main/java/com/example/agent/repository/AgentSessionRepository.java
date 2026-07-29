package com.example.agent.repository;

import com.example.agent.entity.AgentSession;
import com.example.agent.entity.AgentSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    List<AgentSession> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, AgentSessionStatus status);

    Optional<AgentSession> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AgentSession session "
            + "where session.id = :sessionId and session.userId = :userId")
    Optional<AgentSession> findOwnedForUpdate(@Param("sessionId") Long sessionId,
                                               @Param("userId") Long userId);
}
