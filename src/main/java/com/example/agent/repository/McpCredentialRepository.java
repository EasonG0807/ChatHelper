package com.example.agent.repository;

import com.example.agent.entity.McpCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface McpCredentialRepository extends JpaRepository<McpCredential, Long> {

    Optional<McpCredential> findByConnectionIdAndOwnerUserId(Long connectionId, Long ownerUserId);

    boolean existsByConnectionIdAndOwnerUserId(Long connectionId, Long ownerUserId);

    void deleteByConnectionIdAndOwnerUserId(Long connectionId, Long ownerUserId);
}
