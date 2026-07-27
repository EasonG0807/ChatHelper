package com.example.agent.service;

import com.example.agent.entity.AgentMemory;
import com.example.agent.repository.AgentMemoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentMemoryServiceTest {

    @Test
    void retrievesRelevantUserMemoryAndFiltersOtherSessions() throws Exception {
        AgentMemoryRepository repository = mock(AgentMemoryRepository.class);
        AgentMemory durable = memory(1L, null, "项目使用 Java 和 PostgreSQL", "project-stack", 80);
        AgentMemory otherSession = memory(1L, 99L, "当前会话的临时结论", "temporary", 95);
        when(repository.findByUserIdAndActiveTrueOrderByImportanceDescUpdatedAtDesc(1L))
                .thenReturn(List.of(durable, otherSession));

        AgentMemoryService service = new AgentMemoryService(repository, mock(ChatModel.class), new ObjectMapper());
        setField(service, "enabled", true);
        setField(service, "maxRetrievedItems", 6);

        List<AgentMemory> result = service.retrieve(1L, 1L, "Java 项目怎么接 PostgreSQL？", 6);

        assertEquals(List.of(durable), result);
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
