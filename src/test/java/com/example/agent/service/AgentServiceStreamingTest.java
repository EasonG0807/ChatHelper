package com.example.agent.service;

import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentSession;
import com.example.agent.executor.ReActAgentExecutor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceStreamingTest {

    @Test
    void persistsAuthoritativeFinalMarkdownAndKeepsDoneAsLastEvent() {
        AgentSessionService sessionService = mock(AgentSessionService.class);
        AgentStepService stepService = mock(AgentStepService.class);
        ReActAgentExecutor executor = mock(ReActAgentExecutor.class);
        AgentContextManager contextManager = mock(AgentContextManager.class);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        AgentService service = new AgentService(
                sessionService, stepService, executor, contextManager, memoryService);

        AgentSession session = new AgentSession();
        session.setId(10L);
        AgentMessage userMessage = new AgentMessage();
        userMessage.setId(20L);
        String deltaEvent = ReActAgentExecutor.ANSWER_DELTA_EVENT_PREFIX + "{\"text\":\"**完\"}";
        String finalEvent = ReActAgentExecutor.ANSWER_FINAL_EVENT_PREFIX + "{\"markdown\":\"**完成**\"}";

        when(sessionService.getOrCreateSession(1L, 10L)).thenReturn(session);
        when(sessionService.requireOwnedSession(1L, 10L)).thenReturn(session);
        when(sessionService.saveMessage(10L, "user", "问题")).thenReturn(userMessage);
        when(contextManager.build(session, 20L, "问题"))
                .thenReturn(new AgentContextManager.ContextPack(List.of(), "问题", 10));
        when(executor.executeStream(1L, 10L, 20L, "问题", List.of()))
                .thenReturn(Flux.just(deltaEvent, finalEvent));
        when(executor.answerDeltaText(deltaEvent)).thenReturn("**完");
        when(executor.answerFinalMarkdown(finalEvent)).thenReturn("**完成**");

        List<String> events = service.streamAsk(1L, 10L, "问题").collectList().block();

        assertEquals(List.of(deltaEvent, finalEvent, "[DONE]"), events);
        verify(sessionService).saveMessage(10L, "assistant", "**完成**");
        verify(memoryService).scheduleExtraction(1L, 10L, 20L, "问题", "**完成**");
    }
}
