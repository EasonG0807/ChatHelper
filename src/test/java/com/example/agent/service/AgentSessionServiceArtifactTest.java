package com.example.agent.service;

import com.example.agent.entity.AgentSession;
import com.example.agent.repository.AgentArtifactRepository;
import com.example.agent.repository.AgentMessageRepository;
import com.example.agent.repository.AgentSessionRepository;
import com.example.agent.repository.AgentStepRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionServiceArtifactTest {

    @Test
    void clearSessionDeletesArtifactMetadata() {
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        AgentMessageRepository messageRepository = mock(AgentMessageRepository.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentArtifactRepository artifactRepository = mock(AgentArtifactRepository.class);
        AgentSession session = new AgentSession();
        session.setId(10L);
        session.setUserId(1L);
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));
        AgentSessionService service = new AgentSessionService(
                sessionRepository, messageRepository, stepRepository, artifactRepository);

        service.clearSession(1L, 10L);

        verify(artifactRepository).deleteBySessionId(10L);
        verify(messageRepository).deleteBySessionId(10L);
        verify(stepRepository).deleteBySessionId(10L);
    }

    @Test
    void deleteSessionDeletesArtifactMetadataBeforeSession() {
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        AgentMessageRepository messageRepository = mock(AgentMessageRepository.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        AgentArtifactRepository artifactRepository = mock(AgentArtifactRepository.class);
        AgentSession session = new AgentSession();
        session.setId(10L);
        session.setUserId(1L);
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));
        AgentSessionService service = new AgentSessionService(
                sessionRepository, messageRepository, stepRepository, artifactRepository);

        service.deleteSession(1L, 10L);

        verify(artifactRepository).deleteBySessionId(10L);
        verify(sessionRepository).delete(session);
    }
}
