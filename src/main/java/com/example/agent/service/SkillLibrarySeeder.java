package com.example.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Seeds built-in skills from classpath SKILL.md documents on startup.
 * Existing rows are never overwritten, so edits and enable/disable state
 * made at runtime survive restarts.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillLibrarySeeder implements ApplicationRunner {

    private final SkillLibraryService skillLibraryService;

    @Override
    public void run(ApplicationArguments args) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:skills/*/SKILL.md");
            for (Resource resource : resources) {
                seed(resource);
            }
        } catch (Exception ex) {
            log.warn("Skill library seeding failed: {}", ex.getMessage());
        }
    }

    private void seed(Resource resource) {
        try {
            String raw = resource.getContentAsString(StandardCharsets.UTF_8);
            SkillMarkdownParser.ParsedSkill parsed = SkillMarkdownParser.parse(raw);
            skillLibraryService.seedBuiltInIfMissing(parsed.name(), parsed.description(), parsed.body());
        } catch (Exception ex) {
            log.warn("Skipping invalid built-in skill {}: {}", resource.getDescription(), ex.getMessage());
        }
    }
}
