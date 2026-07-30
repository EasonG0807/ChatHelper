package com.example.agent.service;

import com.example.agent.entity.AgentMemory;
import com.example.agent.entity.AgentMemoryStatus;
import com.example.agent.entity.AgentMemoryVerificationStatus;
import com.example.agent.repository.AgentMemoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Owns the fact lifecycle. The model may propose a relationship, but only this
 * service can create versions, supersede facts, record conflicts or invalidate
 * a current fact.
 */
@Service
public class AgentMemoryLifecycleService {

    private static final Set<String> OPERATIONS = Set.of(
            "NEW", "CONFIRM", "REPLACE", "INVALIDATE", "CONFLICT");

    private final AgentMemoryRepository memoryRepository;

    public AgentMemoryLifecycleService(AgentMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Transactional
    public AgentMemory ingest(Long userId, Long sourceMessageId, MemoryCandidate candidate) {
        if (userId == null || candidate == null) {
            return null;
        }
        String scopeKey = scopeKey(candidate.scope(), candidate.sessionId());
        Identity proposed = identity(candidate.subject(), candidate.predicate(), candidate.targetKey(), candidate.type(), candidate.content());
        String operation = normalizedOperation(candidate.operation());

        AgentMemory target = null;
        if (hasText(candidate.targetKey())) {
            target = findCurrent(userId, scopeKey, normalizeMemoryKey(candidate.targetKey()));
        }
        if (target == null) {
            target = findCurrent(userId, scopeKey, proposed.memoryKey());
        }

        if (target != null) {
            proposed = new Identity(target.getSubjectKey(), target.getPredicateKey(), target.getMemoryKey());
        }

        String value = firstText(candidate.value(), candidate.content());
        String fingerprint = fingerprint(value);
        if (target != null && fingerprint.equals(target.getValueFingerprint())) {
            confirm(target, candidate.confidence(), candidate.verificationTtlDays());
            if (hasText(candidate.content())) {
                target.setContent(candidate.content().trim());
            }
            target.setImportance(clamp(candidate.importance(), 0, 100, 50));
            return memoryRepository.save(target);
        }

        if ("INVALIDATE".equals(operation)) {
            if (target != null) {
                invalidateEntity(target, firstText(candidate.content(), "后续事实明确表明该记忆已失效"), false);
                return memoryRepository.save(target);
            }
            return null;
        }

        if (target == null) {
            return memoryRepository.save(newFact(userId, sourceMessageId, candidate, proposed, scopeKey,
                    AgentMemoryStatus.ACTIVE, 1, null));
        }

        if ("REPLACE".equals(operation)) {
            return replace(target, sourceMessageId, candidate, proposed, scopeKey);
        }

        // A changed value is never silently overwritten. Without an explicit
        // REPLACE relation, preserve the current fact and surface a conflict.
        return memoryRepository.save(newFact(userId, sourceMessageId, candidate, proposed, scopeKey,
                AgentMemoryStatus.CONFLICTED, nextVersion(userId, scopeKey, proposed.memoryKey()), target.getId()));
    }

    @Transactional
    public AgentMemory manualReplace(Long userId, Long memoryId, ManualRevision revision) {
        AgentMemory existing = owned(userId, memoryId);
        String targetScopeKey = scopeKey(revision.scope(), revision.sessionId());
        String value = firstText(revision.content(), existing.getFactValue());
        boolean sameFact = targetScopeKey.equals(normalizedScopeKey(existing))
                && safe(existing.getMemoryType()).equals(revision.type())
                && fingerprint(value).equals(existing.getValueFingerprint());

        if (sameFact && statusOf(existing) == AgentMemoryStatus.ACTIVE) {
            existing.setContent(revision.content());
            existing.setFactValue(value);
            existing.setImportance(revision.importance());
            existing.setExpiresAt(revision.expiresAt());
            existing.setVerificationStatus(AgentMemoryVerificationStatus.VERIFIED);
            existing.setLastVerifiedAt(LocalDateTime.now());
            existing.setVerificationDueAt(defaultVerificationDue(revision.type(), null));
            existing.setSourceType("MANUAL");
            return memoryRepository.save(existing);
        }

        AgentMemory currentAtTarget = findCurrent(userId, targetScopeKey, normalizedMemoryKey(existing));
        if (currentAtTarget != null && !currentAtTarget.getId().equals(existing.getId())) {
            supersede(currentAtTarget);
            memoryRepository.saveAndFlush(currentAtTarget);
        }
        if (statusOf(existing) == AgentMemoryStatus.ACTIVE) {
            supersede(existing);
            memoryRepository.saveAndFlush(existing);
        }

        MemoryCandidate candidate = new MemoryCandidate(
                revision.type(), existing.getSubjectKey(), existing.getPredicateKey(), value,
                revision.content(), revision.importance(), 1.0, revision.scope(), revision.sessionId(),
                "REPLACE", existing.getMemoryKey(), "MANUAL", null, null);
        AgentMemory replacement = newFact(userId, existing.getSourceMessageId(), candidate,
                new Identity(existing.getSubjectKey(), existing.getPredicateKey(), normalizedMemoryKey(existing)),
                targetScopeKey, AgentMemoryStatus.ACTIVE,
                nextVersion(userId, targetScopeKey, normalizedMemoryKey(existing)), existing.getId());
        replacement.setExpiresAt(revision.expiresAt());
        replacement.setVerificationStatus(AgentMemoryVerificationStatus.VERIFIED);
        replacement.setLastVerifiedAt(LocalDateTime.now());
        replacement = memoryRepository.save(replacement);
        existing.setReplacedById(replacement.getId());
        memoryRepository.save(existing);
        return replacement;
    }

    @Transactional
    public AgentMemory verify(Long userId, Long memoryId) {
        AgentMemory memory = owned(userId, memoryId);
        if (statusOf(memory) != AgentMemoryStatus.ACTIVE) {
            throw new IllegalArgumentException("只有当前有效版本可以确认；冲突记忆请先处理冲突");
        }
        memory.setVerificationStatus(AgentMemoryVerificationStatus.VERIFIED);
        memory.setLastVerifiedAt(LocalDateTime.now());
        memory.setVerificationDueAt(defaultVerificationDue(memory.getMemoryType(), null));
        memory.setActive(true);
        return memoryRepository.save(memory);
    }

    @Transactional
    public AgentMemory invalidate(Long userId, Long memoryId, String reason) {
        AgentMemory memory = owned(userId, memoryId);
        invalidateEntity(memory, firstText(reason, "用户手动标记为失效"), false);
        return memoryRepository.save(memory);
    }

    @Transactional
    public AgentMemory resolveConflict(Long userId, Long memoryId, String action, String reason) {
        AgentMemory candidate = owned(userId, memoryId);
        if (statusOf(candidate) != AgentMemoryStatus.CONFLICTED) {
            throw new IllegalArgumentException("该记忆不是待处理冲突");
        }
        String normalizedAction = safe(action).trim().toUpperCase(Locale.ROOT);
        if ("KEEP_CURRENT".equals(normalizedAction) || "REJECT".equals(normalizedAction)) {
            invalidateEntity(candidate, firstText(reason, "用户保留旧事实并拒绝冲突候选"), true);
            return memoryRepository.save(candidate);
        }
        if (!"ACCEPT_CANDIDATE".equals(normalizedAction) && !"ACCEPT".equals(normalizedAction)) {
            throw new IllegalArgumentException("冲突处理动作必须是 ACCEPT_CANDIDATE 或 KEEP_CURRENT");
        }

        String scopeKey = normalizedScopeKey(candidate);
        String memoryKey = normalizedMemoryKey(candidate);
        AgentMemory current = findCurrent(userId, scopeKey, memoryKey);
        if (current != null) {
            supersede(current);
            memoryRepository.saveAndFlush(current);
            candidate.setSupersedesId(current.getId());
        }
        candidate.setVersion(nextVersionExcluding(userId, scopeKey, memoryKey, candidate.getId()));
        candidate.setStatus(AgentMemoryStatus.ACTIVE);
        candidate.setVerificationStatus(AgentMemoryVerificationStatus.VERIFIED);
        candidate.setActive(true);
        candidate.setCurrentKey(currentKey(userId, scopeKey, memoryKey));
        candidate.setValidFrom(LocalDateTime.now());
        candidate.setValidTo(null);
        candidate.setInvalidatedAt(null);
        candidate.setInvalidationReason(null);
        candidate.setLastVerifiedAt(LocalDateTime.now());
        candidate.setVerificationDueAt(defaultVerificationDue(candidate.getMemoryType(), null));
        AgentMemory accepted = memoryRepository.save(candidate);
        if (current != null) {
            current.setReplacedById(accepted.getId());
            memoryRepository.save(current);
        }
        return accepted;
    }

    @Transactional
    public List<AgentMemory> listForManagement(Long userId) {
        List<AgentMemory> all = refreshLifecycle(userId);
        return all.stream()
                .filter(memory -> statusOf(memory) != AgentMemoryStatus.SUPERSEDED)
                .sorted(Comparator.comparing(AgentMemory::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public List<AgentMemory> listCurrentForRetrieval(Long userId) {
        return refreshLifecycle(userId).stream()
                .filter(memory -> statusOf(memory) == AgentMemoryStatus.ACTIVE)
                .filter(memory -> verificationOf(memory) == AgentMemoryVerificationStatus.VERIFIED)
                .filter(memory -> Boolean.TRUE.equals(memory.getActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgentMemory> versions(Long userId, Long memoryId) {
        AgentMemory memory = owned(userId, memoryId);
        return memoryRepository.findByUserIdAndScopeKeyAndMemoryKeyOrderByVersionDesc(
                userId, normalizedScopeKey(memory), normalizedMemoryKey(memory));
    }

    @Transactional
    public void deleteSemanticMemory(Long userId, Long memoryId) {
        AgentMemory memory = owned(userId, memoryId);
        if (statusOf(memory) == AgentMemoryStatus.CONFLICTED) {
            memoryRepository.delete(memory);
            return;
        }
        memoryRepository.deleteByUserIdAndScopeKeyAndMemoryKey(
                userId, normalizedScopeKey(memory), normalizedMemoryKey(memory));
    }

    @Transactional
    public void touch(List<AgentMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        memories.forEach(memory -> memory.setLastAccessedAt(now));
        memoryRepository.saveAll(memories);
    }

    public String buildCatalog(Long userId, Long sessionId) {
        String currentSessionScope = scopeKey("SESSION", sessionId);
        return listForManagement(userId).stream()
                .filter(memory -> statusOf(memory) == AgentMemoryStatus.ACTIVE)
                .filter(memory -> "USER".equals(normalizedScopeKey(memory))
                        || currentSessionScope.equals(normalizedScopeKey(memory)))
                .limit(30)
                .map(memory -> "- key=" + normalizedMemoryKey(memory)
                        + ", scope=" + normalizedScopeKey(memory)
                        + ", type=" + memory.getMemoryType()
                        + ", value=" + firstText(memory.getFactValue(), memory.getContent()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private List<AgentMemory> refreshLifecycle(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<AgentMemory> all = new ArrayList<>(memoryRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        LocalDateTime now = LocalDateTime.now();
        Set<String> activeHeads = new HashSet<>();
        all.sort(Comparator
                .comparing((AgentMemory memory) -> hasText(memory.getCurrentKey()) ? 0 : 1)
                .thenComparing(AgentMemory::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        for (AgentMemory memory : all) {
            migrateLegacyFields(memory);
            if (statusOf(memory) == AgentMemoryStatus.ACTIVE
                    && memory.getExpiresAt() != null && !memory.getExpiresAt().isAfter(now)) {
                memory.setStatus(AgentMemoryStatus.EXPIRED);
                memory.setActive(false);
                memory.setCurrentKey(null);
                memory.setValidTo(now);
                memory.setInvalidatedAt(now);
                memory.setInvalidationReason("记忆 TTL 已到期");
            } else if (statusOf(memory) == AgentMemoryStatus.ACTIVE
                    && memory.getVerificationDueAt() != null && !memory.getVerificationDueAt().isAfter(now)) {
                memory.setVerificationStatus(AgentMemoryVerificationStatus.STALE);
            }

            if (statusOf(memory) == AgentMemoryStatus.ACTIVE) {
                String head = currentKey(userId, normalizedScopeKey(memory), normalizedMemoryKey(memory));
                if (!activeHeads.add(head)) {
                    memory.setStatus(AgentMemoryStatus.CONFLICTED);
                    memory.setVerificationStatus(AgentMemoryVerificationStatus.STALE);
                    memory.setActive(false);
                    memory.setCurrentKey(null);
                    memory.setInvalidationReason("检测到历史重复的当前事实，需要人工确认");
                } else {
                    memory.setActive(true);
                    memory.setCurrentKey(head);
                }
            } else {
                memory.setActive(false);
                memory.setCurrentKey(null);
            }
        }
        return memoryRepository.saveAll(all);
    }

    private AgentMemory replace(AgentMemory current,
                                Long sourceMessageId,
                                MemoryCandidate candidate,
                                Identity identity,
                                String scopeKey) {
        supersede(current);
        memoryRepository.saveAndFlush(current);
        AgentMemory replacement = newFact(current.getUserId(), sourceMessageId, candidate, identity, scopeKey,
                AgentMemoryStatus.ACTIVE, nextVersion(current.getUserId(), scopeKey, identity.memoryKey()), current.getId());
        replacement = memoryRepository.save(replacement);
        current.setReplacedById(replacement.getId());
        memoryRepository.save(current);
        return replacement;
    }

    private AgentMemory newFact(Long userId,
                                Long sourceMessageId,
                                MemoryCandidate candidate,
                                Identity identity,
                                String scopeKey,
                                AgentMemoryStatus status,
                                int version,
                                Long relatedId) {
        LocalDateTime now = LocalDateTime.now();
        AgentMemory memory = new AgentMemory();
        memory.setUserId(userId);
        memory.setSessionId("USER".equals(scopeKey) ? null : candidate.sessionId());
        memory.setScopeKey(scopeKey);
        memory.setSourceMessageId(sourceMessageId);
        memory.setSourceType(normalizedSource(candidate.sourceType()));
        memory.setMemoryType(candidate.type());
        memory.setSubjectKey(identity.subject());
        memory.setPredicateKey(identity.predicate());
        memory.setMemoryKey(identity.memoryKey());
        memory.setFactValue(firstText(candidate.value(), candidate.content()));
        memory.setValueFingerprint(fingerprint(memory.getFactValue()));
        memory.setContent(candidate.content());
        memory.setImportance(clamp(candidate.importance(), 0, 100, 50));
        memory.setConfidence(clamp(candidate.confidence(), 0.0, 1.0, 0.7));
        memory.setStatus(status);
        memory.setVersion(Math.max(1, version));
        memory.setSupersedesId(relatedId);
        memory.setConflictWithId(status == AgentMemoryStatus.CONFLICTED ? relatedId : null);
        memory.setActive(status == AgentMemoryStatus.ACTIVE);
        memory.setCurrentKey(status == AgentMemoryStatus.ACTIVE
                ? currentKey(userId, scopeKey, identity.memoryKey()) : null);
        memory.setValidFrom(now);
        memory.setExpiresAt(candidate.ttlDays() != null && candidate.ttlDays() > 0
                ? now.plusDays(Math.min(candidate.ttlDays(), 365)) : null);
        boolean verifiedSource = "USER".equals(memory.getSourceType())
                || "MANUAL".equals(memory.getSourceType())
                || "TOOL".equals(memory.getSourceType());
        memory.setVerificationStatus(verifiedSource
                ? AgentMemoryVerificationStatus.VERIFIED
                : AgentMemoryVerificationStatus.UNVERIFIED);
        memory.setLastVerifiedAt(verifiedSource ? now : null);
        memory.setVerificationDueAt(defaultVerificationDue(candidate.type(), candidate.verificationTtlDays()));
        return memory;
    }

    private void confirm(AgentMemory memory, Double confidence, Integer verificationTtlDays) {
        LocalDateTime now = LocalDateTime.now();
        memory.setVerificationStatus(AgentMemoryVerificationStatus.VERIFIED);
        memory.setLastVerifiedAt(now);
        memory.setVerificationDueAt(defaultVerificationDue(memory.getMemoryType(), verificationTtlDays));
        memory.setConfidence(Math.max(memory.getConfidence() == null ? 0.0 : memory.getConfidence(),
                clamp(confidence, 0.0, 1.0, 0.7)));
        memory.setActive(true);
    }

    private void supersede(AgentMemory memory) {
        memory.setStatus(AgentMemoryStatus.SUPERSEDED);
        memory.setActive(false);
        memory.setCurrentKey(null);
        memory.setValidTo(LocalDateTime.now());
    }

    private void invalidateEntity(AgentMemory memory, String reason, boolean rejected) {
        LocalDateTime now = LocalDateTime.now();
        memory.setStatus(AgentMemoryStatus.INVALIDATED);
        memory.setVerificationStatus(rejected
                ? AgentMemoryVerificationStatus.REJECTED
                : AgentMemoryVerificationStatus.STALE);
        memory.setActive(false);
        memory.setCurrentKey(null);
        memory.setValidTo(now);
        memory.setInvalidatedAt(now);
        memory.setInvalidationReason(reason);
    }

    private AgentMemory findCurrent(Long userId, String scopeKey, String memoryKey) {
        return memoryRepository.findFirstByUserIdAndScopeKeyAndMemoryKeyAndStatusOrderByVersionDesc(
                userId, scopeKey, memoryKey, AgentMemoryStatus.ACTIVE).orElse(null);
    }

    private AgentMemory owned(Long userId, Long memoryId) {
        if (userId == null || memoryId == null) {
            throw new AgentMemoryService.AgentMemoryNotFoundException();
        }
        AgentMemory memory = memoryRepository.findByIdAndUserId(memoryId, userId)
                .orElseThrow(AgentMemoryService.AgentMemoryNotFoundException::new);
        migrateLegacyFields(memory);
        return memory;
    }

    private int nextVersion(Long userId, String scopeKey, String memoryKey) {
        return memoryRepository.findByUserIdAndScopeKeyAndMemoryKeyOrderByVersionDesc(userId, scopeKey, memoryKey)
                .stream()
                .map(AgentMemory::getVersion)
                .filter(version -> version != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private int nextVersionExcluding(Long userId, String scopeKey, String memoryKey, Long excludedId) {
        return memoryRepository.findByUserIdAndScopeKeyAndMemoryKeyOrderByVersionDesc(userId, scopeKey, memoryKey)
                .stream()
                .filter(memory -> excludedId == null || !excludedId.equals(memory.getId()))
                .map(AgentMemory::getVersion)
                .filter(version -> version != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private void migrateLegacyFields(AgentMemory memory) {
        String scopeKey = normalizedScopeKey(memory);
        memory.setScopeKey(scopeKey);
        if (memory.getStatus() == null) {
            memory.setStatus(Boolean.FALSE.equals(memory.getActive())
                    ? AgentMemoryStatus.INVALIDATED : AgentMemoryStatus.ACTIVE);
        }
        if (memory.getVerificationStatus() == null) {
            memory.setVerificationStatus(AgentMemoryVerificationStatus.UNVERIFIED);
        }
        if (memory.getVerificationDueAt() == null && statusOf(memory) == AgentMemoryStatus.ACTIVE) {
            memory.setVerificationDueAt(defaultVerificationDue(memory.getMemoryType(), null));
        }
        if (memory.getVersion() == null || memory.getVersion() < 1) {
            memory.setVersion(1);
        }
        if (!hasText(memory.getFactValue())) {
            memory.setFactValue(memory.getContent());
        }
        if (!hasText(memory.getValueFingerprint())) {
            memory.setValueFingerprint(fingerprint(memory.getFactValue()));
        }
        Identity identity = identity(memory.getSubjectKey(), memory.getPredicateKey(),
                memory.getMemoryKey(), memory.getMemoryType(), memory.getContent());
        memory.setSubjectKey(identity.subject());
        memory.setPredicateKey(identity.predicate());
        memory.setMemoryKey(identity.memoryKey());
        if (memory.getValidFrom() == null) {
            memory.setValidFrom(memory.getCreatedAt() == null ? LocalDateTime.now() : memory.getCreatedAt());
        }
    }

    private Identity identity(String subject, String predicate, String legacyKey, String type, String content) {
        String subjectKey = canonicalPart(subject);
        String predicateKey = canonicalPart(predicate);
        String normalizedLegacy = normalizeMemoryKey(legacyKey);
        if ((!hasText(subjectKey) || !hasText(predicateKey)) && hasText(normalizedLegacy)) {
            int separator = normalizedLegacy.indexOf("::");
            if (separator > 0 && separator < normalizedLegacy.length() - 2) {
                subjectKey = normalizedLegacy.substring(0, separator);
                predicateKey = normalizedLegacy.substring(separator + 2);
            } else {
                List<String> parts = new ArrayList<>(List.of(normalizedLegacy.split("\\.")));
                parts.removeIf(String::isBlank);
                if (parts.size() > 1) {
                    predicateKey = parts.remove(parts.size() - 1);
                    subjectKey = String.join(".", parts);
                } else if (!parts.isEmpty()) {
                    subjectKey = canonicalPart(type);
                    predicateKey = parts.get(0);
                }
            }
        }
        if (!hasText(subjectKey)) {
            subjectKey = canonicalPart(type);
        }
        if (!hasText(predicateKey)) {
            predicateKey = "fact." + fingerprint(content).substring(0, 16);
        }
        subjectKey = truncate(subjectKey, 120);
        predicateKey = truncate(predicateKey, 120);
        return new Identity(subjectKey, predicateKey, truncate(subjectKey + "::" + predicateKey, 180));
    }

    private String normalizeMemoryKey(String key) {
        if (!hasText(key)) {
            return "";
        }
        String normalized = Normalizer.normalize(key, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("::")) {
            String[] parts = normalized.split("::", 2);
            return canonicalPart(parts[0]) + "::" + canonicalPart(parts.length > 1 ? parts[1] : "fact");
        }
        return canonicalPart(normalized);
    }

    private String normalizedMemoryKey(AgentMemory memory) {
        return identity(memory.getSubjectKey(), memory.getPredicateKey(), memory.getMemoryKey(),
                memory.getMemoryType(), memory.getContent()).memoryKey();
    }

    private String canonicalPart(String value) {
        if (!hasText(value)) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", ".")
                .replaceAll("^\\.+|\\.+$", "")
                .replaceAll("\\.{2,}", ".");
    }

    private String scopeKey(String scope, Long sessionId) {
        return "USER".equalsIgnoreCase(safe(scope)) ? "USER" : "SESSION:" + sessionId;
    }

    private String normalizedScopeKey(AgentMemory memory) {
        if (hasText(memory.getScopeKey())) {
            return memory.getScopeKey();
        }
        return memory.getSessionId() == null ? "USER" : "SESSION:" + memory.getSessionId();
    }

    private String normalizedOperation(String operation) {
        String value = safe(operation).trim().toUpperCase(Locale.ROOT);
        return OPERATIONS.contains(value) ? value : "NEW";
    }

    private String normalizedSource(String source) {
        String value = safe(source).trim().toUpperCase(Locale.ROOT);
        return Set.of("USER", "ASSISTANT", "TOOL", "MANUAL", "SYSTEM").contains(value)
                ? value : "ASSISTANT";
    }

    private AgentMemoryStatus statusOf(AgentMemory memory) {
        if (memory.getStatus() != null) {
            return memory.getStatus();
        }
        return Boolean.FALSE.equals(memory.getActive())
                ? AgentMemoryStatus.INVALIDATED : AgentMemoryStatus.ACTIVE;
    }

    private AgentMemoryVerificationStatus verificationOf(AgentMemory memory) {
        return memory.getVerificationStatus() == null
                ? AgentMemoryVerificationStatus.UNVERIFIED : memory.getVerificationStatus();
    }

    private LocalDateTime defaultVerificationDue(String type, Integer requestedDays) {
        int days;
        if (requestedDays != null && requestedDays > 0) {
            days = Math.min(requestedDays, 365);
        } else {
            days = switch (safe(type).toUpperCase(Locale.ROOT)) {
                case "TODO" -> 14;
                case "PROJECT_FACT" -> 30;
                case "EPISODIC" -> 90;
                case "USER_PREFERENCE" -> 180;
                default -> 90;
            };
        }
        return LocalDateTime.now().plusDays(days);
    }

    private String currentKey(Long userId, String scopeKey, String memoryKey) {
        return sha256(userId + "|" + scopeKey + "|" + memoryKey);
    }

    private String fingerprint(String value) {
        String normalized = Normalizer.normalize(safe(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return sha256(normalized);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to build memory identity", ex);
        }
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first.trim() : safe(second).trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int max) {
        String safe = safe(value);
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        return Math.max(min, Math.min(max, value == null ? fallback : value));
    }

    private double clamp(Double value, double min, double max, double fallback) {
        return Math.max(min, Math.min(max, value == null ? fallback : value));
    }

    private record Identity(String subject, String predicate, String memoryKey) {
    }

    public record MemoryCandidate(String type,
                                  String subject,
                                  String predicate,
                                  String value,
                                  String content,
                                  Integer importance,
                                  Double confidence,
                                  String scope,
                                  Long sessionId,
                                  String operation,
                                  String targetKey,
                                  String sourceType,
                                  Integer ttlDays,
                                  Integer verificationTtlDays) {
    }

    public record ManualRevision(String type,
                                 String content,
                                 Integer importance,
                                 String scope,
                                 Long sessionId,
                                 LocalDateTime expiresAt) {
    }
}
