package com.example.agent.controller;

import com.example.agent.service.AgentRunConflictException;
import com.example.agent.service.AgentRunCoordinator;
import com.example.agent.service.AgentRunEventStreamService;
import com.example.agent.service.AgentRunNotFoundException;
import com.example.agent.service.AgentRunService;
import com.example.demo.service.ImageQuestionContext;
import com.example.demo.service.ImageQuestionContextService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRunService runService;
    private final AgentRunCoordinator runCoordinator;
    private final AgentRunEventStreamService eventStreamService;
    private final ImageQuestionContextService imageQuestionContextService;

    @PostMapping("/runs")
    @ResponseBody
    public ResponseEntity<?> create(@RequestParam Long sessionId,
                                    @RequestParam String question,
                                    @RequestParam(required = false) String imageContextId,
                                    HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Please login first."));
        }
        try {
            ImageQuestionContext imageContext = imageQuestionContextService.find(userId, imageContextId);
            AgentRunService.RunView run = runService.create(userId, sessionId, question, imageContext);
            runCoordinator.dispatch(run.id());
            return ResponseEntity.accepted().body(run);
        } catch (AgentRunConflictException conflict) {
            return ResponseEntity.status(409).body(Map.of("message", conflict.getMessage()));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("message", invalid.getMessage()));
        }
    }

    @GetMapping("/runs/{runId}")
    @ResponseBody
    public ResponseEntity<?> status(@PathVariable Long runId, HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Please login first."));
        }
        try {
            return ResponseEntity.ok(runService.getOwned(userId, runId));
        } catch (AgentRunNotFoundException missing) {
            return ResponseEntity.status(404).body(Map.of("message", missing.getMessage()));
        }
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<ServerSentEvent<String>> events(
            @PathVariable Long runId,
            @RequestParam(defaultValue = "0") long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpSession httpSession,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Long userId = userId(httpSession);
        if (userId == null) {
            return Flux.just(ServerSentEvent.builder("Please login first.").build(),
                    ServerSentEvent.builder("[DONE]").build());
        }
        long cursor = Math.max(after, parseCursor(lastEventId));
        try {
            return eventStreamService.stream(userId, runId, cursor);
        } catch (AgentRunNotFoundException missing) {
            return Flux.just(ServerSentEvent.builder("任务不存在或无权访问。").build(),
                    ServerSentEvent.builder("[DONE]").build());
        }
    }

    @GetMapping("/runs/summary")
    @ResponseBody
    public ResponseEntity<?> summaries(HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Please login first."));
        }
        return ResponseEntity.ok(runService.summaries(userId).values());
    }

    @PostMapping("/sessions/{sessionId}/read")
    @ResponseBody
    public ResponseEntity<?> markRead(@PathVariable Long sessionId, HttpSession httpSession) {
        Long userId = userId(httpSession);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Please login first."));
        }
        try {
            runService.markSessionRead(userId, sessionId);
            return ResponseEntity.ok(Map.of("read", true));
        } catch (IllegalArgumentException missing) {
            return ResponseEntity.status(404).body(Map.of("message", missing.getMessage()));
        }
    }

    private Long userId(HttpSession session) {
        return (Long) session.getAttribute("uid");
    }

    private long parseCursor(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
