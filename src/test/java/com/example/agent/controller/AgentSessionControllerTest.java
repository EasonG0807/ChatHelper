package com.example.agent.controller;

import com.example.agent.entity.AgentSession;
import com.example.agent.service.AgentSessionNotFoundException;
import com.example.agent.service.AgentSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentSessionControllerTest {

    private AgentSessionService sessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sessionService = mock(AgentSessionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentSessionController(sessionService)).build();
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/agent/session/10/rename").param("title", "新名称"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renamesOwnedSession() throws Exception {
        AgentSession renamed = new AgentSession();
        renamed.setId(10L);
        renamed.setUserId(1L);
        renamed.setTitle("项目复盘 🚀");
        when(sessionService.renameSession(1L, 10L, "项目复盘 🚀")).thenReturn(renamed);

        mockMvc.perform(post("/agent/session/10/rename")
                        .sessionAttr("uid", 1L)
                        .param("title", "项目复盘 🚀"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.title").value("项目复盘 🚀"));

        verify(sessionService).renameSession(1L, 10L, "项目复盘 🚀");
    }

    @Test
    void returnsBadRequestForInvalidTitle() throws Exception {
        doThrow(new IllegalArgumentException("会话名称不能为空。"))
                .when(sessionService).renameSession(1L, 10L, " ");

        mockMvc.perform(post("/agent/session/10/rename")
                        .sessionAttr("uid", 1L)
                        .param("title", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("会话名称不能为空。"));
    }

    @Test
    void hidesMissingAndUnownedSessionsAsNotFound() throws Exception {
        doThrow(new AgentSessionNotFoundException("Agent session not found."))
                .when(sessionService).renameSession(2L, 10L, "新名称");

        mockMvc.perform(post("/agent/session/10/rename")
                        .sessionAttr("uid", 2L)
                        .param("title", "新名称"))
                .andExpect(status().isNotFound());
    }
}
