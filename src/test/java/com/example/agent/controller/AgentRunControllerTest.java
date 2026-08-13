package com.example.agent.controller;

import com.example.agent.entity.AgentRunStatus;
import com.example.agent.service.AgentRunCoordinator;
import com.example.agent.service.AgentRunEventStreamService;
import com.example.agent.service.AgentRunService;
import com.example.demo.service.ImageQuestionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentRunControllerTest {

    private AgentRunService runService;
    private AgentRunCoordinator coordinator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        runService = mock(AgentRunService.class);
        coordinator = mock(AgentRunCoordinator.class);
        AgentRunController controller = new AgentRunController(
                runService,
                coordinator,
                mock(AgentRunEventStreamService.class),
                mock(ImageQuestionContextService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createsAndDispatchesDurableRun() throws Exception {
        when(runService.create(1L, 10L, "分析任务", null)).thenReturn(run());

        mockMvc.perform(post("/agent/runs")
                        .sessionAttr("uid", 1L)
                        .param("sessionId", "10")
                        .param("question", "分析任务"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(30L))
                .andExpect(jsonPath("$.status").value("QUEUED"));

        verify(coordinator).dispatch(30L);
    }

    @Test
    void rejectsAnonymousSubmission() throws Exception {
        mockMvc.perform(post("/agent/runs")
                        .param("sessionId", "10")
                        .param("question", "分析任务"))
                .andExpect(status().isUnauthorized());
    }

    private AgentRunService.RunView run() {
        return new AgentRunService.RunView(
                30L, 1L, 10L, 20L, null,
                "分析任务", "分析任务", AgentRunStatus.QUEUED, 0L,
                null, LocalDateTime.now(), null, null, null);
    }
}
