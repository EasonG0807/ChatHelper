package com.example.agent.service;

import com.example.agent.entity.AgentSkillDoc;
import com.example.agent.repository.AgentSkillDocRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The skill library: shared built-in skills plus per-user skills.
 *
 * Only skill metadata (name + description) is ever injected into the planner
 * system prompt; the full instruction body is loaded on demand through the
 * use_skill tool. A user skill with the same name as a built-in shadows it.
 */
@Service
@RequiredArgsConstructor
public class SkillLibraryService {

    private static final int MAX_CONTENT_CHARS = 20000;

    private final AgentSkillDocRepository skillRepository;

    /** Built-ins plus the user's own skills, including disabled ones (management view). */
    public List<AgentSkillDoc> listVisibleSkills(Long userId) {
        List<AgentSkillDoc> result = new ArrayList<>(skillRepository.findByOwnerUserIdIsNullOrderByIdAsc());
        if (userId != null) {
            result.addAll(skillRepository.findByOwnerUserIdOrderByIdAsc(userId));
        }
        return result;
    }

    /** Enabled skills the agent may load for this user; a user skill shadows a built-in of the same name. */
    public List<AgentSkillDoc> listActiveSkills(Long userId) {
        Map<String, AgentSkillDoc> byName = new LinkedHashMap<>();
        for (AgentSkillDoc skill : listVisibleSkills(userId)) {
            if (Boolean.TRUE.equals(skill.getEnabled())) {
                byName.put(skill.getName(), skill);
            }
        }
        return List.copyOf(byName.values());
    }

    /** Metadata catalog for the planner system prompt: one line per skill, body excluded. */
    public String catalog(Long userId) {
        List<AgentSkillDoc> skills = listActiveSkills(userId);
        if (skills.isEmpty()) {
            return "(no skills registered)";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentSkillDoc skill : skills) {
            builder.append("- ").append(skill.getName()).append(": ")
                    .append(oneLine(skill.getDescription())).append('\n');
        }
        return builder.toString().strip();
    }

    public Optional<AgentSkillDoc> load(Long userId, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.strip();
        return listActiveSkills(userId).stream()
                .filter(skill -> skill.getName().equals(wanted))
                .findFirst();
    }

    @Transactional
    public AgentSkillDoc createFromMarkdown(Long userId, String rawMarkdown) {
        SkillMarkdownParser.ParsedSkill parsed = SkillMarkdownParser.parse(rawMarkdown);
        return create(userId, parsed.name(), parsed.description(), parsed.body());
    }

    @Transactional
    public AgentSkillDoc create(Long userId, String name, String description, String content) {
        if (userId == null) {
            throw new IllegalArgumentException("User is required to create a skill.");
        }
        SkillMarkdownParser.validateName(name);
        requireText(description, "description");
        requireText(content, "content");
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("Skill content exceeds " + MAX_CONTENT_CHARS + " characters.");
        }
        if (skillRepository.findByNameAndOwnerUserId(name, userId).isPresent()) {
            throw new IllegalArgumentException("You already have a skill named '" + name + "'.");
        }
        AgentSkillDoc skill = new AgentSkillDoc();
        skill.setName(name);
        skill.setDescription(description.strip());
        skill.setContent(content.strip());
        skill.setOwnerUserId(userId);
        skill.setBuiltIn(false);
        skill.setEnabled(true);
        return skillRepository.save(skill);
    }

    @Transactional
    public AgentSkillDoc update(Long userId, Long skillId, String description, String content) {
        AgentSkillDoc skill = requireOwn(userId, skillId);
        requireText(description, "description");
        requireText(content, "content");
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("Skill content exceeds " + MAX_CONTENT_CHARS + " characters.");
        }
        skill.setDescription(description.strip());
        skill.setContent(content.strip());
        return skillRepository.save(skill);
    }

    @Transactional
    public AgentSkillDoc setEnabled(Long userId, boolean isAdmin, Long skillId, boolean enabled) {
        AgentSkillDoc skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
        if (skill.getOwnerUserId() == null) {
            if (!isAdmin) {
                throw new IllegalArgumentException("Only an admin can toggle a built-in skill.");
            }
        } else if (!skill.isOwnedBy(userId)) {
            throw new IllegalArgumentException("You can only toggle your own skills.");
        }
        skill.setEnabled(enabled);
        return skillRepository.save(skill);
    }

    @Transactional
    public void delete(Long userId, Long skillId) {
        AgentSkillDoc skill = requireOwn(userId, skillId);
        skillRepository.delete(skill);
    }

    /** Seed a built-in skill only when absent, so admin/user changes survive restarts. */
    @Transactional
    public void seedBuiltInIfMissing(String name, String description, String content) {
        if (skillRepository.findByNameAndOwnerUserIdIsNull(name).isPresent()) {
            return;
        }
        AgentSkillDoc skill = new AgentSkillDoc();
        skill.setName(name);
        skill.setDescription(description);
        skill.setContent(content);
        skill.setOwnerUserId(null);
        skill.setBuiltIn(true);
        skill.setEnabled(true);
        skillRepository.save(skill);
    }

    private AgentSkillDoc requireOwn(Long userId, Long skillId) {
        AgentSkillDoc skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
        if (skill.isBuiltInSkill() || !skill.isOwnedBy(userId)) {
            throw new IllegalArgumentException("Built-in skills cannot be modified; you can only edit your own skills.");
        }
        return skill;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Skill " + field + " must not be blank.");
        }
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }
}
