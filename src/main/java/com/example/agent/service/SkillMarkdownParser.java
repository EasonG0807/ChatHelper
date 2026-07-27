package com.example.agent.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SKILL.md documents: a YAML-like frontmatter block holding the
 * metadata (name, description) followed by the markdown instruction body.
 *
 * <pre>
 * ---
 * name: document-qa
 * description: Grounded Q&amp;A over uploaded private documents.
 * ---
 * ## Workflow
 * ...
 * </pre>
 */
public final class SkillMarkdownParser {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "\\A\\s*---\\s*\\R(.*?)\\R\\s*---\\s*\\R?(.*)\\z", Pattern.DOTALL);
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");

    private SkillMarkdownParser() {
    }

    public record ParsedSkill(String name, String description, String body) {
    }

    /**
     * @throws IllegalArgumentException when the document has no frontmatter,
     *                                  no valid name, or an empty body.
     */
    public static ParsedSkill parse(String raw) {
        String safe = raw == null ? "" : raw.strip();
        Matcher matcher = FRONTMATTER.matcher(safe);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "SKILL.md must start with a frontmatter block: ---\\nname: ...\\ndescription: ...\\n---");
        }
        String name = null;
        String description = null;
        for (String line : matcher.group(1).split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase();
            String value = stripQuotes(line.substring(colon + 1).strip());
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                default -> {
                    // Unknown frontmatter keys are ignored so the format stays forward-compatible.
                }
            }
        }
        String body = matcher.group(2).strip();
        validateName(name);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Skill frontmatter must include a non-empty description.");
        }
        if (body.isBlank()) {
            throw new IllegalArgumentException("Skill body must not be empty; it holds the actual instructions.");
        }
        return new ParsedSkill(name, description, body);
    }

    public static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Skill name must be a slug of lowercase letters, digits, '-' or '_' (2-64 chars), e.g. document-qa");
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
