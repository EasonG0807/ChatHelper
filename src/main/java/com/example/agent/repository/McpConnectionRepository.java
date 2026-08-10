package com.example.agent.repository;

import com.example.agent.entity.McpConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface McpConnectionRepository extends JpaRepository<McpConnection, Long> {

    List<McpConnection> findByOwnerUserIdOrderByCreatedAtDescIdDesc(Long ownerUserId);

    List<McpConnection> findByOwnerUserIdAndEnabledTrueOrderByIdAsc(Long ownerUserId);

    Optional<McpConnection> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    Optional<McpConnection> findByOwnerUserIdAndNameIgnoreCase(Long ownerUserId, String name);

    long countByOwnerUserId(Long ownerUserId);
}
