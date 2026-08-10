package com.example.agent.repository;

import com.example.agent.entity.McpDiscoveredTool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface McpDiscoveredToolRepository extends JpaRepository<McpDiscoveredTool, Long> {

    List<McpDiscoveredTool> findByConnectionIdOrderByExposedNameAsc(Long connectionId);

    List<McpDiscoveredTool> findByConnectionIdInAndEnabledTrueOrderByExposedNameAsc(Collection<Long> connectionIds);

    Optional<McpDiscoveredTool> findByConnectionIdAndRemoteName(Long connectionId, String remoteName);

    void deleteByConnectionId(Long connectionId);
}
