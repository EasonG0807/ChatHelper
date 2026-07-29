package com.example.agent.controller;

import com.example.agent.entity.AgentSession;
import com.example.agent.service.AgentSessionNotFoundException;
import com.example.agent.service.AgentSessionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/agent/session")
@RequiredArgsConstructor
public class AgentSessionController {

    private final AgentSessionService sessionService;

    @PostMapping("/{sessionId}/rename")
    @ResponseBody
    public ResponseEntity<?> rename(@PathVariable Long sessionId,
                                    @RequestParam String title,
                                    HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body(new ApiError("请先登录。"));
        }
        try {
            AgentSession renamed = sessionService.renameSession(userId, sessionId, title);
            return ResponseEntity.ok(new RenameSessionResponse(
                    renamed.getId(), renamed.getTitle(), renamed.getUpdatedAt()));
        } catch (AgentSessionNotFoundException notFound) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException invalidTitle) {
            return ResponseEntity.badRequest().body(new ApiError(invalidTitle.getMessage()));
        }
    }

    public record RenameSessionResponse(Long id, String title, LocalDateTime updatedAt) {
    }

    public record ApiError(String message) {
    }
}
