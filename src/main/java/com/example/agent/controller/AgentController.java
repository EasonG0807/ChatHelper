package com.example.agent.controller;

import com.example.agent.entity.AgentSession;
import com.example.agent.entity.AgentToolConfig;
import com.example.agent.entity.AgentToolSource;
import com.example.agent.service.AgentService;
import com.example.agent.service.AgentArtifactService;
import com.example.agent.service.AgentMemoryService;
import com.example.agent.service.AgentSessionService;
import com.example.agent.service.AgentStepService;
import com.example.agent.service.AgentToolManagementService;
import com.example.agent.executor.ReActAgentExecutor;
import com.example.agent.service.SkillLibraryService;
import com.example.agent.tool.AgentToolRegistry;
import com.example.agent.tool.react.AgentWorkspaceService;
import com.example.demo.service.ImageQuestionContext;
import com.example.demo.service.ImageQuestionContextService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentSessionService sessionService;
    private final AgentArtifactService artifactService;
    private final AgentStepService stepService;
    private final AgentService agentService;
    private final AgentMemoryService memoryService;
    private final AgentToolRegistry toolRegistry;
    private final ImageQuestionContextService imageQuestionContextService;
    private final SkillLibraryService skillLibraryService;
    private final AgentToolManagementService toolManagementService;
    private final AgentWorkspaceService workspaceService;

    @Value("${agent.admin.user-ids:1}")
    private String adminUserIds;

    @GetMapping
    public String index(@RequestParam(required = false) Long sessionId, HttpSession httpSession, Model model) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }

        toolRegistry.syncToolConfigs();
        AgentSession activeSession = sessionService.getOrCreateSession(userId, sessionId);
        model.addAttribute("activeSession", activeSession);
        model.addAttribute("skills", skillLibraryService.listActiveSkills(userId));
        model.addAttribute("sessions", sessionService.listActiveSessions(userId));
        model.addAttribute("messages", sessionService.listMessages(activeSession.getId()));
        model.addAttribute("steps", stepService.listSteps(activeSession.getId()));
        model.addAttribute("artifacts", artifactService.list(userId, activeSession.getId()));
        return "agent";
    }

    @PostMapping("/session/create")
    public String createSession(@RequestParam(required = false) String title,
                                HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        AgentSession session = sessionService.createSession(userId, title);
        return "redirect:/agent?sessionId=" + session.getId();
    }

    @PostMapping("/session/{sessionId}/delete")
    public String deleteSession(@PathVariable Long sessionId, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        try {
            memoryService.clearSessionMemories(userId, sessionId);
            sessionService.deleteSession(userId, sessionId);
            workspaceService.deleteWorkspace(userId, sessionId);
        } catch (IllegalArgumentException ignored) {
            // Session already gone or not owned by this user; the list below reflects reality.
        }
        return "redirect:/agent";
    }

    @GetMapping(value = "/ask", produces = "text/event-stream")
    @ResponseBody
    public Flux<String> ask(@RequestParam Long sessionId,
                            @RequestParam String question,
                            @RequestParam(required = false) String imageContextId,
                            HttpSession httpSession,
                            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return Flux.just("Please login first.", "[DONE]");
        }
        ImageQuestionContext imageContext = imageQuestionContextService.find(userId, imageContextId);
        return agentService.streamAsk(userId, sessionId, question, imageContext)
                .map(this::escapeSseChunk);
    }

    /**
     * Structured progress/answer/heartbeat frames are JSON and already safe
     * for SSE transport. Plain chunks are retained only as a rolling-deploy
     * compatibility path and still need newline escaping.
     */
    private String escapeSseChunk(String chunk) {
        if (ReActAgentExecutor.isStructuredEvent(chunk) || "[DONE]".equals(chunk)) {
            return chunk;
        }
        return chunk.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @PostMapping("/image-context")
    @ResponseBody
    public ResponseEntity<?> uploadImageContext(@RequestParam("file") MultipartFile file, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        try {
            ImageQuestionContext context = imageQuestionContextService.save(userId, file);
            return ResponseEntity.ok(Map.of(
                    "id", context.id(),
                    "webPath", context.webPath(),
                    "description", context.description()
            ));
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    @GetMapping("/steps")
    @ResponseBody
    public ResponseEntity<?> steps(@RequestParam Long sessionId, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        AgentSession session = sessionService.getOrCreateSession(userId, sessionId);
        return ResponseEntity.ok(stepService.listSteps(session.getId()));
    }

    @GetMapping("/admin")
    public String admin(HttpSession httpSession, Model model) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        if (!isAdmin(userId)) {
            return "redirect:/agent";
        }
        List<AgentToolConfig> tools = toolManagementService.listTools();
        model.addAttribute("tools", tools);
        model.addAttribute("toolCount", tools.size());
        model.addAttribute("mcpToolCount", tools.stream()
                .filter(tool -> tool.getToolSource() == AgentToolSource.MCP)
                .count());
        model.addAttribute("enabledToolCount", tools.stream()
                .filter(tool -> Boolean.TRUE.equals(tool.getEnabled()))
                .count());
        return "agent-admin";
    }

    @GetMapping("/tools")
    @ResponseBody
    public ResponseEntity<?> tools(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        return ResponseEntity.ok(toolManagementService.listTools());
    }

    @PostMapping("/tools/{toolName}/enabled")
    @ResponseBody
    public ResponseEntity<?> setToolEnabled(@PathVariable String toolName,
                                            @RequestParam boolean enabled,
                                            HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body("Admin permission is required.");
        }
        return ResponseEntity.ok(toolManagementService.setToolEnabled(toolName, enabled));
    }

    @PostMapping("/admin/tools/{toolName}/enabled")
    public String setToolEnabledFromAdmin(@PathVariable String toolName,
                                          @RequestParam boolean enabled,
                                          HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        if (!isAdmin(userId)) {
            return "redirect:/agent";
        }
        toolManagementService.setToolEnabled(toolName, enabled);
        return "redirect:/agent/admin";
    }

    @GetMapping("/skills")
    public String skillLibrary(HttpSession httpSession, Model model) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        model.addAttribute("skills", skillLibraryService.listVisibleSkills(userId));
        model.addAttribute("currentUserId", userId);
        model.addAttribute("isAdmin", isAdmin(userId));
        return "agent-skills";
    }

    @GetMapping("/skills/list")
    @ResponseBody
    public ResponseEntity<?> listSkills(HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        return ResponseEntity.ok(skillLibraryService.listActiveSkills(userId).stream()
                .map(skill -> Map.of(
                        "name", skill.getName(),
                        "description", skill.getDescription() == null ? "" : skill.getDescription(),
                        "builtIn", skill.isBuiltInSkill()))
                .toList());
    }

    @PostMapping("/skills/create")
    public String createSkill(@RequestParam(required = false) String rawMarkdown,
                              @RequestParam(required = false) String name,
                              @RequestParam(required = false) String description,
                              @RequestParam(required = false) String content,
                              HttpSession httpSession,
                              RedirectAttributes redirectAttributes) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        try {
            if (rawMarkdown != null && !rawMarkdown.isBlank()) {
                skillLibraryService.createFromMarkdown(userId, rawMarkdown);
            } else {
                skillLibraryService.create(userId, name, description, content);
            }
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("skillError", ex.getMessage());
        }
        return "redirect:/agent/skills";
    }

    @PostMapping("/skills/{skillId}/update")
    public String updateSkill(@PathVariable Long skillId,
                              @RequestParam String description,
                              @RequestParam String content,
                              HttpSession httpSession,
                              RedirectAttributes redirectAttributes) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        try {
            skillLibraryService.update(userId, skillId, description, content);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("skillError", ex.getMessage());
        }
        return "redirect:/agent/skills";
    }

    @PostMapping("/skills/{skillId}/enabled")
    public String setSkillEnabled(@PathVariable Long skillId,
                                  @RequestParam boolean enabled,
                                  HttpSession httpSession,
                                  RedirectAttributes redirectAttributes) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        try {
            skillLibraryService.setEnabled(userId, isAdmin(userId), skillId, enabled);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("skillError", ex.getMessage());
        }
        return "redirect:/agent/skills";
    }

    @PostMapping("/skills/{skillId}/delete")
    public String deleteSkill(@PathVariable Long skillId,
                              HttpSession httpSession,
                              RedirectAttributes redirectAttributes) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return "redirect:/auth/login";
        }
        try {
            skillLibraryService.delete(userId, skillId);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("skillError", ex.getMessage());
        }
        return "redirect:/agent/skills";
    }

    @PostMapping("/clear")
    @ResponseBody
    public ResponseEntity<String> clear(@RequestParam Long sessionId, HttpSession httpSession) {
        Long userId = (Long) httpSession.getAttribute("uid");
        if (userId == null) {
            return ResponseEntity.status(401).body("Please login first.");
        }
        sessionService.clearSession(userId, sessionId);
        memoryService.clearSessionMemories(userId, sessionId);
        workspaceService.deleteWorkspace(userId, sessionId);
        return ResponseEntity.ok("Agent session cleared.");
    }

    private boolean isAdmin(Long userId) {
        if (userId == null || adminUserIds == null || adminUserIds.isBlank()) {
            return false;
        }
        return Arrays.stream(adminUserIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> value.equals(String.valueOf(userId)));
    }
}
