package com.example.agent.repository;

import com.example.agent.entity.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    List<AgentMessage> findBySessionIdOrderByIdAsc(Long sessionId);

    Optional<AgentMessage> findFirstBySessionIdAndRoleAndIdGreaterThanOrderByIdDesc(
            Long sessionId, String role, Long afterId);

    void deleteBySessionId(Long sessionId);
}
