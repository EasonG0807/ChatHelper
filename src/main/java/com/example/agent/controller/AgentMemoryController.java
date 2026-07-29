package com.example.agent.controller;

import com.example.agent.service.AgentMemoryService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/agent/memories")
@RequiredArgsConstructor
public class AgentMemoryController {

    private final AgentMemoryService memoryService;

    @GetMapping
    public ResponseEntity<?> list(HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(memoryService.listMemoryViews(userId));
    }

    @PutMapping("/{memoryId}")
    public ResponseEntity<?> update(@PathVariable Long memoryId,
                                    @RequestBody MemoryUpdateRequest request,
                                    HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return unauthorized();
        }
        try {
            AgentMemoryService.MemoryUpdate update = new AgentMemoryService.MemoryUpdate(
                    request.memoryType(), request.content(), request.importance(), request.scope(),
                    request.sessionId(), request.expiresAt());
            return ResponseEntity.ok(memoryService.updateMemory(userId, memoryId, update));
        } catch (AgentMemoryService.AgentMemoryNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<?> delete(@PathVariable Long memoryId, HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return unauthorized();
        }
        try {
            memoryService.deleteMemory(userId, memoryId);
            return ResponseEntity.ok(Map.of("message", "记忆已删除"));
        } catch (AgentMemoryService.AgentMemoryNotFoundException ex) {
            return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/clear")
    public ResponseEntity<?> clearSession(@RequestParam Long sessionId, HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return unauthorized();
        }
        try {
            memoryService.clearSessionMemories(userId, sessionId);
            return ResponseEntity.ok(Map.of("message", "当前会话记忆已清空"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/clear-all")
    public ResponseEntity<?> clearAll(HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return unauthorized();
        }
        memoryService.clearAllMemories(userId);
        return ResponseEntity.ok(Map.of("message", "全部长期记忆已清空"));
    }

    private Long userId(HttpSession httpSession) {
        return (Long) httpSession.getAttribute("uid");
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("message", "请先登录"));
    }

    public record MemoryUpdateRequest(String memoryType,
                                      String content,
                                      Integer importance,
                                      String scope,
                                      Long sessionId,
                                      LocalDateTime expiresAt) {
    }
}
