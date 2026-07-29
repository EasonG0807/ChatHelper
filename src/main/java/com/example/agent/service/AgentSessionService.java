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

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private static final int MAX_SESSION_TITLE_CODE_POINTS = 100;

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

    @Transactional
    public AgentSession renameSession(Long userId, Long sessionId, String title) {
        if (userId == null || sessionId == null) {
            throw sessionNotFound();
        }
        AgentSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(this::sessionNotFound);
        session.setTitle(normalizeSessionTitle(title));
        session.setUpdatedAt(LocalDateTime.now());
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
        AgentSession session = sessionRepository.findOwnedForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent session not found"));
        messageRepository.deleteBySessionId(session.getId());
        stepRepository.deleteBySessionId(session.getId());
        artifactRepository.deleteBySessionId(session.getId());
        sessionRepository.delete(session);
    }

    @Transactional
    public void clearSession(Long userId, Long sessionId) {
        AgentSession session = sessionRepository.findOwnedForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent session not found"));
        messageRepository.deleteBySessionId(session.getId());
        stepRepository.deleteBySessionId(session.getId());
        artifactRepository.deleteBySessionId(session.getId());
        session.setConversationSummary(null);
        session.setSummarizedMessageId(null);
        sessionRepository.save(session);
    }

    private String normalizeSessionTitle(String title) {
        if (title == null) {
            throw new IllegalArgumentException("会话名称不能为空。");
        }
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFC);
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("会话名称不能包含换行或控制字符。");
        }
        normalized = stripUnicodeSpaces(normalized);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("会话名称不能为空。");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_SESSION_TITLE_CODE_POINTS) {
            throw new IllegalArgumentException("会话名称不能超过 100 个字符。");
        }
        return normalized;
    }

    private String stripUnicodeSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeSpace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeSpace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private boolean isUnicodeSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private AgentSessionNotFoundException sessionNotFound() {
        return new AgentSessionNotFoundException("Agent session not found.");
    }
}
