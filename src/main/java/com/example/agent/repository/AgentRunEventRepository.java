package com.example.agent.repository;

import com.example.agent.entity.AgentRunEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AgentRunEventRepository extends JpaRepository<AgentRunEvent, Long> {

    List<AgentRunEvent> findTop500ByRunIdAndSequenceGreaterThanOrderBySequenceAsc(Long runId, Long sequence);

    void deleteByRunId(Long runId);

    void deleteByRunIdIn(Collection<Long> runIds);
}
