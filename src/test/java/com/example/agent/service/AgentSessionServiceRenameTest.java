package com.example.agent.service;

import com.example.agent.entity.AgentSession;
import com.example.agent.repository.AgentArtifactRepository;
import com.example.agent.repository.AgentMessageRepository;
import com.example.agent.repository.AgentRunEventRepository;
import com.example.agent.repository.AgentRunRepository;
import com.example.agent.repository.AgentSessionRepository;
import com.example.agent.repository.AgentStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionServiceRenameTest {

    private AgentSessionRepository sessionRepository;
    private AgentSessionService service;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AgentSessionRepository.class);
        service = new AgentSessionService(
                sessionRepository,
                mock(AgentMessageRepository.class),
                mock(AgentStepRepository.class),
                mock(AgentArtifactRepository.class),
                mock(AgentRunRepository.class),
                mock(AgentRunEventRepository.class));
        session = new AgentSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setTitle("旧名称");
        when(sessionRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void renamesOwnedSessionAndPreservesChineseEmoji() {
        String decomposed = "  项目复盘 e\u0301 🚀  ";

        AgentSession renamed = service.renameSession(1L, 10L, decomposed);

        assertEquals(Normalizer.normalize("项目复盘 é 🚀", Normalizer.Form.NFC), renamed.getTitle());
        verify(sessionRepository).save(session);
    }

    @Test
    void rejectsBlankControlCharactersAndOverlongTitles() {
        assertThrows(IllegalArgumentException.class, () -> service.renameSession(1L, 10L, "   "));
        assertThrows(IllegalArgumentException.class, () -> service.renameSession(1L, 10L, "\u00a0\u2007\u202f"));
        assertThrows(IllegalArgumentException.class, () -> service.renameSession(1L, 10L, "第一行\n第二行"));
        assertThrows(IllegalArgumentException.class, () -> service.renameSession(1L, 10L, "会".repeat(101)));

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void acceptsExactlyOneHundredUnicodeCodePoints() {
        String title = "🚀".repeat(100);

        AgentSession renamed = service.renameSession(1L, 10L, title);

        assertEquals(title, renamed.getTitle());
    }

    @Test
    void cannotRenameAnotherUsersSession() {
        when(sessionRepository.findByIdAndUserId(10L, 2L)).thenReturn(Optional.empty());

        assertThrows(AgentSessionNotFoundException.class,
                () -> service.renameSession(2L, 10L, "不应成功"));

        verify(sessionRepository, never()).save(any());
    }
}
