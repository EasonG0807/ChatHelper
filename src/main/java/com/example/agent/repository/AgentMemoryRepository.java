package com.example.agent.repository;

import com.example.agent.entity.AgentMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentMemoryRepository extends JpaRepository<AgentMemory, Long> {

    List<AgentMemory> findByUserIdAndActiveTrueOrderByImportanceDescUpdatedAtDesc(Long userId);

    Optional<AgentMemory> findFirstByUserIdAndMemoryKeyAndActiveTrue(Long userId, String memoryKey);

    List<AgentMemory> findByUserIdAndSessionIdAndActiveTrueOrderByUpdatedAtDesc(Long userId, Long sessionId);

    void deleteByUserIdAndSessionId(Long userId, Long sessionId);
}
