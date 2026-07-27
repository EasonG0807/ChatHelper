package com.example.agent.service;

import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentSession;
import com.example.agent.entity.AgentSessionStatus;
import com.example.agent.repository.AgentMessageRepository;
import com.example.agent.repository.AgentArtifactRepository;
import com.example.agent.repository.AgentSessionRepository;
import com.example.agent.repository.AgentStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private final AgentSessionRepository sessionRepository;
    private final AgentMessageRepository messageRepository;
    private final AgentStepRepository stepRepository;
    private final AgentArtifactRepository artifactRepository;

    public List<AgentSession> listActiveSessions(Long userId) {
        return sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, AgentSessionStatus.ACTIVE);
    }

    @Transactional
    public AgentSession getOrCreateSession(Long userId, Long sessionId) {
        if (sessionId != null) {
            return sessionRepository.findByIdAndUserId(sessionId, userId)
                    .orElseGet(() -> createSession(userId, "AI Super Agent"));
        }
        List<AgentSession> sessions = listActiveSessions(userId);
        if (!sessions.isEmpty()) {
            return sessions.get(0);
        }
        return createSession(userId, "AI Super Agent");
    }

    @Transactional
    public AgentSession createSession(Long userId, String title) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setTitle((title == null || title.isBlank()) ? "AI Super Agent" : title);
        return sessionRepository.save(session);
    }

    public List<AgentMessage> listMessages(Long sessionId) {
        return messageRepository.findBySessionIdOrderByIdAsc(sessionId);
    }

    @Transactional
    public AgentMessage saveMessage(Long sessionId, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
        });
        return messageRepository.save(message);
    }

    @Transactional
    public AgentSession updateSummary(Long sessionId, String conversationSummary, Long summarizedMessageId) {
        AgentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Agent session not found"));
        session.setConversationSummary(conversationSummary);
        session.setSummarizedMessageId(summarizedMessageId);
        return sessionRepository.save(session);
    }

    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        AgentSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent session not found"));
        messageRepository.deleteBySessionId(session.getId());
        stepRepository.deleteBySessionId(session.getId());
        artifactRepository.deleteBySessionId(session.getId());
        sessionRepository.delete(session);
    }

    @Transactional
    public void clearSession(Long userId, Long sessionId) {
        AgentSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent session not found"));
        messageRepository.deleteBySessionId(session.getId());
        stepRepository.deleteBySessionId(session.getId());
        artifactRepository.deleteBySessionId(session.getId());
        session.setConversationSummary(null);
        session.setSummarizedMessageId(null);
        sessionRepository.save(session);
    }
}
