package com.example.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunRegistryTest {

    @Test
    void artifactMutationWaitsUntilEveryRunLeaseIsClosed() {
        AgentRunRegistry registry = new AgentRunRegistry();

        try (AgentRunRegistry.Lease ignored = registry.beginRun(1L, 10L)) {
            assertTrue(registry.tryBeginArtifactMutation(1L, 10L).isEmpty());
        }

        AgentRunRegistry.Lease mutation = registry.tryBeginArtifactMutation(1L, 10L).orElseThrow();
        mutation.close();
    }
}
