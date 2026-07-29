package com.example.agent.controller;

import com.example.agent.service.AgentMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentMemoryControllerTest {

    private AgentMemoryService memoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        memoryService = mock(AgentMemoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentMemoryController(memoryService)).build();
    }

    @Test
    void requiresLoginForEveryMutationAndList() throws Exception {
        mockMvc.perform(get("/agent/memories"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/agent/memories/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/agent/memories/7"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/agent/memories/clear").param("sessionId", "10"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/agent/memories/clear-all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listsManagementSafeMemoryViews() throws Exception {
        AgentMemoryService.MemoryView view = memoryView();
        when(memoryService.listMemoryViews(1L)).thenReturn(List.of(view));

        mockMvc.perform(get("/agent/memories").sessionAttr("uid", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].content").value("项目使用 PostgreSQL"))
                .andExpect(jsonPath("$[0].scope").value("USER"))
                .andExpect(content().string(not(containsString("userId"))));
    }

    @Test
    void updatesOwnedMemoryFromJson() throws Exception {
        when(memoryService.updateMemory(eq(1L), eq(7L), any(AgentMemoryService.MemoryUpdate.class)))
                .thenReturn(memoryView());

        mockMvc.perform(put("/agent/memories/7")
                        .sessionAttr("uid", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memoryType":"PROJECT_FACT",
                                  "content":"项目使用 PostgreSQL",
                                  "importance":90,
                                  "scope":"USER",
                                  "sessionId":null,
                                  "expiresAt":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void returnsNotFoundForUnownedMemory() throws Exception {
        when(memoryService.updateMemory(eq(2L), eq(7L), any(AgentMemoryService.MemoryUpdate.class)))
                .thenThrow(new AgentMemoryService.AgentMemoryNotFoundException());

        mockMvc.perform(put("/agent/memories/7")
                        .sessionAttr("uid", 2L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memoryType":"PROJECT_FACT","content":"x","importance":50,"scope":"USER"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesOneAndClearsRequestedScopes() throws Exception {
        mockMvc.perform(delete("/agent/memories/7").sessionAttr("uid", 1L))
                .andExpect(status().isOk());
        mockMvc.perform(post("/agent/memories/clear")
                        .sessionAttr("uid", 1L)
                        .param("sessionId", "10"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/agent/memories/clear-all").sessionAttr("uid", 1L))
                .andExpect(status().isOk());

        verify(memoryService).deleteMemory(1L, 7L);
        verify(memoryService).clearSessionMemories(1L, 10L);
        verify(memoryService).clearAllMemories(1L);
    }

    private AgentMemoryService.MemoryView memoryView() {
        return new AgentMemoryService.MemoryView(
                7L, "PROJECT_FACT", "项目使用 PostgreSQL", "project-stack",
                90, 0.9, "USER", null, null,
                LocalDateTime.of(2026, 7, 29, 10, 0),
                LocalDateTime.of(2026, 7, 29, 10, 30), false);
    }
}
