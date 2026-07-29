package com.example.agent.service;

import com.example.agent.entity.AgentMemory;
import com.example.agent.entity.AgentSession;
import com.example.agent.repository.AgentMemoryRepository;
import com.example.agent.repository.AgentSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryServiceTest {

    @Test
    void retrievesRelevantUserMemoryAndFiltersOtherSessions() throws Exception {
        AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        AgentMemory durable = memory(1L, null, "项目使用 Java 和 PostgreSQL", "project-stack", 80);
        AgentMemory otherSession = memory(1L, 99L, "当前会话的临时结论", "temporary", 95);
        when(repository.findByUserIdAndActiveTrueOrderByImportanceDescUpdatedAtDesc(1L))
                .thenReturn(List.of(durable, otherSession));

        AgentMemoryService service = new AgentMemoryService(
                repository, sessionRepository, mock(ChatModel.class), new ObjectMapper());
        setField(service, "enabled", true);
        setField(service, "maxRetrievedItems", 6);

        List<AgentMemory> result = service.retrieve(1L, 1L, "Java 项目怎么接 PostgreSQL？", 6);

        assertEquals(List.of(durable), result);
    }

    @Test
    void updatesOnlyOwnedMemoryAndCanPromoteItToUserScope() throws Exception {
        AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        AgentMemory existing = memory(1L, 10L, "旧内容", "project-stack", 50);
        existing.setId(7L);
        when(repository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(AgentMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AgentMemoryService service = new AgentMemoryService(
                repository, sessionRepository, mock(ChatModel.class), new ObjectMapper());

        AgentMemoryService.MemoryView updated = service.updateMemory(1L, 7L,
                new AgentMemoryService.MemoryUpdate(
                        "decision", "项目统一使用 PostgreSQL", 88, "USER", null, null));

        assertEquals("DECISION", updated.memoryType());
        assertEquals("项目统一使用 PostgreSQL", updated.content());
        assertEquals(88, updated.importance());
        assertEquals("USER", updated.scope());
        assertNull(updated.sessionId());
        verify(repository).save(existing);
    }

    @Test
    void rejectsUnownedMemoryAndUnownedTargetSession() {
        AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
        AgentSessionRepository sessionRepository = mock(AgentSessionRepository.class);
        AgentMemoryService service = new AgentMemoryService(
                repository, sessionRepository, mock(ChatModel.class), new ObjectMapper());
        when(repository.findByIdAndUserId(7L, 2L)).thenReturn(Optional.empty());

        assertThrows(AgentMemoryService.AgentMemoryNotFoundException.class,
                () -> service.deleteMemory(2L, 7L));

        AgentMemory existing = memory(1L, null, "项目事实", "project", 70);
        existing.setId(8L);
        when(repository.findByIdAndUserId(8L, 1L)).thenReturn(Optional.of(existing));
        when(sessionRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateMemory(1L, 8L,
                        new AgentMemoryService.MemoryUpdate(
                                "PROJECT_FACT", "项目事实", 70, "SESSION", 99L, null)));
    }

    @Test
    void clearsAllMemoriesForCurrentUserOnly() {
        AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
        AgentMemoryService service = new AgentMemoryService(
                repository, mock(AgentSessionRepository.class), mock(ChatModel.class), new ObjectMapper());

        service.clearAllMemories(3L);

        verify(repository).deleteByUserId(3L);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private AgentMemory memory(Long userId, Long sessionId, String content, String key, int importance) {
        AgentMemory memory = new AgentMemory();
        memory.setUserId(userId);
        memory.setSessionId(sessionId);
        memory.setContent(content);
        memory.setMemoryKey(key);
        memory.setMemoryType("PROJECT_FACT");
        memory.setImportance(importance);
        memory.setConfidence(0.9);
        memory.setActive(true);
        memory.setUpdatedAt(LocalDateTime.now());
        return memory;
    }
}
