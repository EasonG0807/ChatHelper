package com.example.agent.repository;

import com.example.agent.entity.AgentMemory;
import com.example.agent.entity.AgentMemoryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, Long> {

    List<AgentMemory> findByUserIdAndActiveTrueOrderByImportanceDescUpdatedAtDesc(Long userId);

    Optional<AgentMemory> findFirstByUserIdAndMemoryKeyAndActiveTrue(Long userId, String memoryKey);

    Optional<AgentMemory> findFirstByUserIdAndScopeKeyAndMemoryKeyAndStatusOrderByVersionDesc(
            Long userId, String scopeKey, String memoryKey, AgentMemoryStatus status);

    List<AgentMemory> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<AgentMemory> findByUserIdAndScopeKeyAndMemoryKeyOrderByVersionDesc(
            Long userId, String scopeKey, String memoryKey);

    Optional<AgentMemory> findByIdAndUserId(Long id, Long userId);

    List<AgentMemory> findByUserIdAndSessionIdAndActiveTrueOrderByUpdatedAtDesc(Long userId, Long sessionId);

    void deleteByUserIdAndSessionId(Long userId, Long sessionId);

    void deleteByUserIdAndScopeKeyAndMemoryKey(Long userId, String scopeKey, String memoryKey);

    void deleteByUserId(Long userId);
}
