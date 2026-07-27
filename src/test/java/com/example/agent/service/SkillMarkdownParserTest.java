package com.example.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillMarkdownParserTest {

    @Test
    void parsesFrontmatterAndBody() {
        SkillMarkdownParser.ParsedSkill skill = SkillMarkdownParser.parse("""
                ---
                name: weekly-report
                description: 整理周报
                ---
                ## 工作流
                1. 收集本周记录
                """);
        assertEquals("weekly-report", skill.name());
        assertEquals("整理周报", skill.description());
        assertEquals("## 工作流\n1. 收集本周记录", skill.body());
    }

    @Test
    void stripsQuotesAndIgnoresUnknownKeys() {
        SkillMarkdownParser.ParsedSkill skill = SkillMarkdownParser.parse("""
                ---
                name: "doc-qa"
                description: '文档问答'
                version: 3
                ---
                body text
                """);
        assertEquals("doc-qa", skill.name());
        assertEquals("文档问答", skill.description());
    }

    @Test
    void rejectsMissingFrontmatter() {
        assertThrows(IllegalArgumentException.class,
                () -> SkillMarkdownParser.parse("just a markdown body"));
    }

    @Test
    void rejectsInvalidName() {
        assertThrows(IllegalArgumentException.class, () -> SkillMarkdownParser.parse("""
                ---
                name: Bad Name!
                description: desc
                ---
                body
                """));
    }

    @Test
    void rejectsEmptyBody() {
        assertThrows(IllegalArgumentException.class, () -> SkillMarkdownParser.parse("""
                ---
                name: empty-body
                description: desc
                ---
                """));
    }

    @Test
    void validateNameAcceptsSlugAndRejectsUppercase() {
        SkillMarkdownParser.validateName("doc_qa-2");
        assertThrows(IllegalArgumentException.class, () -> SkillMarkdownParser.validateName("DocQA"));
        assertThrows(IllegalArgumentException.class, () -> SkillMarkdownParser.validateName(null));
    }
}
