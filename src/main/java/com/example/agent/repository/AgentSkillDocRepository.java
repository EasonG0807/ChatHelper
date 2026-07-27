package com.example.agent.repository;

import com.example.agent.entity.AgentSkillDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentSkillDocRepository extends JpaRepository<AgentSkillDoc, Long> {

    List<AgentSkillDoc> findByOwnerUserIdIsNullOrderByIdAsc();

    List<AgentSkillDoc> findByOwnerUserIdOrderByIdAsc(Long ownerUserId);

    Optional<AgentSkillDoc> findByNameAndOwnerUserIdIsNull(String name);

    Optional<AgentSkillDoc> findByNameAndOwnerUserId(String name, Long ownerUserId);
}
