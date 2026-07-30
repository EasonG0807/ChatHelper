package com.example.agent.service;

import com.example.agent.entity.AgentMemory;
import com.example.agent.entity.AgentMemoryStatus;
import com.example.agent.entity.AgentMemoryVerificationStatus;
import com.example.agent.repository.AgentMemoryRepository;
import com.example.agent.repository.AgentSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts durable facts from a conversation and retrieves only the memories
 * relevant to the current request. Raw messages remain the source of truth;
 * memories are compact, searchable projections of that transcript.
 */
@Service
public class AgentMemoryService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "USER_PREFERENCE", "PROJECT_FACT", "DECISION", "TODO", "CONSTRAINT", "EPISODIC");
    private static final Pattern JSON_ARRAY = Pattern.compile("(?s)\\[.*\\]");
    private static final int MAX_MEMORY_CONTENT_LENGTH = 800;

    private final AgentMemoryRepository memoryRepository;
    private final AgentSessionRepository sessionRepository;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final AgentMemoryLifecycleService lifecycleService;

    @Autowired
    public AgentMemoryService(AgentMemoryRepository memoryRepository,
                              AgentSessionRepository sessionRepository,
                              @Qualifier("agentDeepSeekChatModel") ChatModel chatModel,
                              ObjectMapper objectMapper,
                              AgentMemoryLifecycleService lifecycleService) {
        this.memoryRepository = memoryRepository;
        this.sessionRepository = sessionRepository;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.lifecycleService = lifecycleService;
    }

    /** Kept for focused unit construction outside Spring. */
    public AgentMemoryService(AgentMemoryRepository memoryRepository,
                              AgentSessionRepository sessionRepository,
                              ChatModel chatModel,
                              ObjectMapper objectMapper) {
        this(memoryRepository, sessionRepository, chatModel, objectMapper,
                new AgentMemoryLifecycleService(memoryRepository));
    }

    @Value("${agent.memory.enabled:true}")
    private boolean enabled;

    @Value("${agent.memory.max-items-per-run:5}")
    private int maxItemsPerRun;

    @Value("${agent.memory.max-retrieved-items:6}")
    private int maxRetrievedItems;

    public List<AgentMemory> listActiveMemories(Long userId) {
        if (!enabled || userId == null) {
            return List.of();
        }
        return lifecycleService.listCurrentForRetrieval(userId);
    }

    /** Returns management-safe views without exposing the owning user id. */
    public List<MemoryView> listMemoryViews(Long userId) {
        if (userId == null) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        return lifecycleService.listForManagement(userId).stream()
                .map(memory -> MemoryView.from(memory, now))
                .toList();
    }

    public List<AgentMemory> retrieve(Long userId, Long sessionId, String question, int limit) {
        if (!enabled || userId == null || question == null || question.isBlank()) {
            return List.of();
        }

        Set<String> queryTerms = terms(question);
        LocalDateTime now = LocalDateTime.now();
        int safeLimit = Math.max(1, Math.min(limit, maxRetrievedItems));
        List<ScoredMemory> scored = new ArrayList<>();
        for (AgentMemory memory : lifecycleService.listCurrentForRetrieval(userId)) {
            if (memory.getSessionId() != null && !memory.getSessionId().equals(sessionId)) {
                continue;
            }
            if (memory.getExpiresAt() != null && memory.getExpiresAt().isBefore(now)) {
                continue;
            }

            Set<String> memoryTerms = terms(memory.getContent() + " " + nullToEmpty(memory.getMemoryKey()));
            long overlap = memoryTerms.stream().filter(queryTerms::contains).count();
            double lexical = queryTerms.isEmpty() ? 0.0 : (double) overlap / queryTerms.size();
            double importance = clamp(memory.getImportance() == null ? 50 : memory.getImportance(), 0, 100) / 100.0;
            double confidence = clamp(memory.getConfidence() == null ? 0.5 : memory.getConfidence(), 0.0, 1.0);
            double recency = recencyScore(memory.getUpdatedAt(), now);
            double score = lexical * 0.60 + importance * 0.20 + confidence * 0.15 + recency * 0.05;
            if (overlap > 0 || importance >= 0.85) {
                scored.add(new ScoredMemory(memory, score));
            }
        }

        List<AgentMemory> result = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(safeLimit)
                .map(ScoredMemory::memory)
                .toList();
        lifecycleService.touch(result);
        return result;
    }

    /** Schedule extraction after the user-facing response has completed. */
    public void scheduleExtraction(Long userId,
                                   Long sessionId,
                                   Long sourceMessageId,
                                   String userMessage,
                                   String assistantMessage) {
        if (!enabled || userId == null || (userMessage == null && assistantMessage == null)) {
            return;
        }
        Mono.fromRunnable(() -> extractAndPersist(
                        userId, sessionId, sourceMessageId, userMessage, assistantMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(null, error -> {
                    // Memory extraction is best-effort and must never fail the answer path.
                });
    }

    @Transactional
    public void clearSessionMemories(Long userId, Long sessionId) {
        if (userId != null && sessionId != null) {
            if (sessionRepository.findByIdAndUserId(sessionId, userId).isEmpty()) {
                throw new IllegalArgumentException("会话不存在或无权访问");
            }
            memoryRepository.deleteByUserIdAndSessionId(userId, sessionId);
        }
    }

    @Transactional
    public MemoryView updateMemory(Long userId, Long memoryId, MemoryUpdate update) {
        if (userId == null || memoryId == null) {
            throw new AgentMemoryNotFoundException();
        }
        AgentMemory memory = memoryRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(AgentMemoryNotFoundException::new);
        if (update == null) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }

        String type = nullToEmpty(update.memoryType()).trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的记忆类型");
        }
        String content = nullToEmpty(update.content()).trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        if (content.length() > MAX_MEMORY_CONTENT_LENGTH) {
            throw new IllegalArgumentException("记忆内容不能超过 " + MAX_MEMORY_CONTENT_LENGTH + " 个字符");
        }
        int importance = update.importance() == null ? 50 : update.importance();
        if (importance < 0 || importance > 100) {
            throw new IllegalArgumentException("重要度必须在 0 到 100 之间");
        }

        String scope = nullToEmpty(update.scope()).trim().toUpperCase(Locale.ROOT);
        if (!"USER".equals(scope) && !"SESSION".equals(scope)) {
            throw new IllegalArgumentException("记忆作用域必须是 USER 或 SESSION");
        }
        Long targetSessionId = null;
        if ("SESSION".equals(scope)) {
            targetSessionId = update.sessionId();
            if (targetSessionId == null || sessionRepository.findByIdAndUserId(targetSessionId, userId).isEmpty()) {
                throw new IllegalArgumentException("目标会话不存在或无权访问");
            }
        }

        AgentMemory replacement = lifecycleService.manualReplace(userId, memoryId,
                new AgentMemoryLifecycleService.ManualRevision(
                        type, content, importance, scope, targetSessionId, update.expiresAt()));
        return MemoryView.from(replacement, LocalDateTime.now());
    }

    @Transactional
    public void deleteMemory(Long userId, Long memoryId) {
        if (userId == null || memoryId == null) {
            throw new AgentMemoryNotFoundException();
        }
        lifecycleService.deleteSemanticMemory(userId, memoryId);
    }

    public List<MemoryView> listVersions(Long userId, Long memoryId) {
        LocalDateTime now = LocalDateTime.now();
        return lifecycleService.versions(userId, memoryId).stream()
                .map(memory -> MemoryView.from(memory, now))
                .toList();
    }

    public MemoryView verifyMemory(Long userId, Long memoryId) {
        return MemoryView.from(lifecycleService.verify(userId, memoryId), LocalDateTime.now());
    }

    public MemoryView invalidateMemory(Long userId, Long memoryId, String reason) {
        return MemoryView.from(lifecycleService.invalidate(userId, memoryId, reason), LocalDateTime.now());
    }

    public MemoryView resolveConflict(Long userId, Long memoryId, String action, String reason) {
        return MemoryView.from(lifecycleService.resolveConflict(userId, memoryId, action, reason), LocalDateTime.now());
    }

    @Transactional
    public void clearAllMemories(Long userId) {
        if (userId != null) {
            memoryRepository.deleteByUserId(userId);
        }
    }

    private void extractAndPersist(Long userId,
                                   Long sessionId,
                                   Long sourceMessageId,
                                   String userMessage,
                                   String assistantMessage) {
        String memoryCatalog = lifecycleService.buildCatalog(userId, sessionId);
        String raw = ChatClient.create(chatModel)
                .prompt()
                .system("""
                        You extract durable memories from a Chinese AI agent conversation.
                        Return only a JSON array, with at most 5 items.
                        Record only stable, useful information: user preferences, project facts,
                        technical decisions, constraints, unresolved TODOs, or important task episodes.
                        Do not record greetings, temporary wording, secrets, credentials, or guesses.
                        Do not store transient runtime errors, service availability, file existence,
                        dependency availability or environment health as durable memory. Those states
                        must be checked from their source of truth when needed.
                        Each item must have: type, subject, predicate, value, content,
                        importance (0-100), confidence (0-1), scope (USER or SESSION),
                        operation, targetKey, sourceType, ttlDays (optional), verificationTtlDays (optional).
                        Valid types: USER_PREFERENCE, PROJECT_FACT, DECISION, TODO, CONSTRAINT, EPISODIC.
                        Valid operations:
                        - NEW: no existing fact describes this subject and predicate.
                        - CONFIRM: the conversation confirms an existing fact with the same value.
                        - REPLACE: an explicit newer fact replaces an existing fact.
                        - INVALIDATE: an existing fact is explicitly no longer true and has no replacement.
                        - CONFLICT: the new statement contradicts an existing fact but the newer truth is uncertain.
                        Use the exact existing key as targetKey for CONFIRM, REPLACE, INVALIDATE or CONFLICT.
                        Do not invent a different key for an existing subject and predicate.
                        subject and predicate must be short stable semantic identifiers, not full sentences.
                        sourceType must be USER, ASSISTANT or TOOL according to where the fact came from.
                        If there is nothing durable, return [].
                        """)
                .user("""
                        Current active memory catalog:
                        %s

                        User message:
                        %s

                        Assistant answer:
                        %s

                        Extract only memories that are safe and useful for future context.
                        """.formatted(memoryCatalog.isBlank() ? "(empty)" : memoryCatalog,
                        nullToEmpty(userMessage), nullToEmpty(assistantMessage)))
                .call()
                .content();

        if (raw == null || raw.isBlank()) {
            return;
        }
        Matcher matcher = JSON_ARRAY.matcher(raw);
        if (!matcher.find()) {
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(matcher.group());
            if (!root.isArray()) {
                return;
            }
            int saved = 0;
            for (JsonNode node : root) {
                if (saved >= Math.max(1, maxItemsPerRun)) {
                    break;
                }
                if (persistNode(userId, sessionId, sourceMessageId, node)) {
                    saved++;
                }
            }
        } catch (Exception ignored) {
            // Invalid model output is intentionally ignored; the transcript remains intact.
        }
    }

    private boolean persistNode(Long userId, Long sessionId, Long sourceMessageId, JsonNode node) {
        String type = node.path("type").asText("").trim().toUpperCase(Locale.ROOT);
        String content = node.path("content").asText("").trim();
        if (!ALLOWED_TYPES.contains(type) || content.isBlank()) {
            return false;
        }

        content = truncate(content, MAX_MEMORY_CONTENT_LENGTH);
        String scope = node.path("scope").asText("SESSION").trim().toUpperCase(Locale.ROOT);
        Long targetSessionId = "USER".equals(scope) ? null : sessionId;
        String targetKey = node.path("targetKey").asText("").trim();
        if (targetKey.isBlank()) {
            targetKey = node.path("key").asText("").trim();
        }
        AgentMemory saved = lifecycleService.ingest(userId, sourceMessageId,
                new AgentMemoryLifecycleService.MemoryCandidate(
                        type,
                        node.path("subject").asText("").trim(),
                        node.path("predicate").asText("").trim(),
                        node.path("value").asText(content).trim(),
                        content,
                        node.path("importance").asInt(50),
                        node.path("confidence").asDouble(0.7),
                        scope,
                        targetSessionId,
                        node.path("operation").asText("NEW"),
                        targetKey,
                        node.path("sourceType").asText("ASSISTANT"),
                        optionalPositiveInt(node, "ttlDays"),
                        optionalPositiveInt(node, "verificationTtlDays")));
        return saved != null;
    }

    private Integer optionalPositiveInt(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        int value = node.path(field).asInt(0);
        return value > 0 ? value : null;
    }

    private Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        StringBuilder ascii = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint) && Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) {
                ascii.appendCodePoint(Character.toLowerCase(codePoint));
                continue;
            }
            if (!ascii.isEmpty()) {
                result.add(ascii.toString());
                ascii.setLength(0);
            }
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                result.add(new String(Character.toChars(codePoint)));
            }
        }
        if (!ascii.isEmpty()) {
            result.add(ascii.toString());
        }
        return result;
    }

    private double recencyScore(LocalDateTime updatedAt, LocalDateTime now) {
        if (updatedAt == null) {
            return 0.0;
        }
        long days = Math.max(0, java.time.Duration.between(updatedAt, now).toDays());
        return 1.0 / (1.0 + days / 30.0);
    }

    private String normalizeKey(String value) {
        return truncate(value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim(), 180);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ScoredMemory(AgentMemory memory, double score) {
    }

    public record MemoryUpdate(String memoryType,
                               String content,
                               Integer importance,
                               String scope,
                               Long sessionId,
                               LocalDateTime expiresAt) {
    }

    public record MemoryView(Long id,
                             String memoryType,
                             String content,
                             String memoryKey,
                             Integer importance,
                             Double confidence,
                             String scope,
                             Long sessionId,
                             LocalDateTime expiresAt,
                             LocalDateTime createdAt,
                             LocalDateTime updatedAt,
                             boolean expired,
                             String status,
                             String verificationStatus,
                             Integer version,
                             String subjectKey,
                             String predicateKey,
                             Long supersedesId,
                             Long replacedById,
                             Long conflictWithId,
                             LocalDateTime lastVerifiedAt,
                             LocalDateTime verificationDueAt,
                             String invalidationReason,
                             String sourceType) {

        /** Backward-compatible constructor for existing controller tests and callers. */
        public MemoryView(Long id,
                          String memoryType,
                          String content,
                          String memoryKey,
                          Integer importance,
                          Double confidence,
                          String scope,
                          Long sessionId,
                          LocalDateTime expiresAt,
                          LocalDateTime createdAt,
                          LocalDateTime updatedAt,
                          boolean expired) {
            this(id, memoryType, content, memoryKey, importance, confidence, scope, sessionId,
                    expiresAt, createdAt, updatedAt, expired, "ACTIVE", "UNVERIFIED", 1,
                    null, null, null, null, null, null, null, null, null);
        }

        private static MemoryView from(AgentMemory memory, LocalDateTime now) {
            LocalDateTime expiresAt = memory.getExpiresAt();
            AgentMemoryStatus status = memory.getStatus() == null
                    ? (Boolean.FALSE.equals(memory.getActive()) ? AgentMemoryStatus.INVALIDATED : AgentMemoryStatus.ACTIVE)
                    : memory.getStatus();
            AgentMemoryVerificationStatus verificationStatus = memory.getVerificationStatus() == null
                    ? AgentMemoryVerificationStatus.UNVERIFIED : memory.getVerificationStatus();
            return new MemoryView(
                    memory.getId(),
                    memory.getMemoryType(),
                    memory.getContent(),
                    memory.getMemoryKey(),
                    memory.getImportance(),
                    memory.getConfidence(),
                    memory.getSessionId() == null ? "USER" : "SESSION",
                    memory.getSessionId(),
                    expiresAt,
                    memory.getCreatedAt(),
                    memory.getUpdatedAt(),
                    status == AgentMemoryStatus.EXPIRED || (expiresAt != null && expiresAt.isBefore(now)),
                    status.name(),
                    verificationStatus.name(),
                    memory.getVersion(),
                    memory.getSubjectKey(),
                    memory.getPredicateKey(),
                    memory.getSupersedesId(),
                    memory.getReplacedById(),
                    memory.getConflictWithId(),
                    memory.getLastVerifiedAt(),
                    memory.getVerificationDueAt(),
                    memory.getInvalidationReason(),
                    memory.getSourceType());
        }
    }

    public static class AgentMemoryNotFoundException extends RuntimeException {
        public AgentMemoryNotFoundException() {
            super("记忆不存在或无权访问");
        }
    }
}
