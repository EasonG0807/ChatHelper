package com.example.agent.repository;

import com.example.agent.entity.AgentRun;
import com.example.agent.entity.AgentRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRun, Long> {

    Optional<AgentRun> findByIdAndUserId(Long id, Long userId);

    Optional<AgentRun> findFirstByUserIdAndSessionIdAndStatusInOrderByCreatedAtDescIdDesc(
            Long userId, Long sessionId, Collection<AgentRunStatus> statuses);

    List<AgentRun> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long userId);

    List<AgentRun> findByStatusOrderByCreatedAtAscIdAsc(AgentRunStatus status);

    List<AgentRun> findBySessionIdOrderByCreatedAtAscIdAsc(Long sessionId);

    boolean existsByUserIdAndSessionIdAndStatusIn(Long userId, Long sessionId,
                                                   Collection<AgentRunStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AgentRun run where run.id = :runId")
    Optional<AgentRun> findByIdForUpdate(@Param("runId") Long runId);

    @Modifying
    @Query("update AgentRun run set run.readAt = :readAt "
            + "where run.userId = :userId and run.sessionId = :sessionId "
            + "and run.readAt is null and run.status in :statuses")
    int markRead(@Param("userId") Long userId,
                 @Param("sessionId") Long sessionId,
                 @Param("readAt") LocalDateTime readAt,
                 @Param("statuses") Collection<AgentRunStatus> statuses);

    void deleteBySessionId(Long sessionId);
}
