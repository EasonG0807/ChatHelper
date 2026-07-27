package com.example.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationQueryRewriterTest {

    private final ConversationQueryRewriter rewriter = new ConversationQueryRewriter();

    @Test
    void rewritesShortFollowUpWithRecentUserRequests() {
        String rewritten = rewriter.rewrite(
                "这个怎么做？",
                List.of(
                        new ConversationQueryRewriter.Turn("user", "我在设计一个 Java Agent 的记忆模块"),
                        new ConversationQueryRewriter.Turn("assistant", "可以拆成短期上下文和长期记忆"),
                        new ConversationQueryRewriter.Turn("user", "需要支持多轮对话")
                ));

        assertTrue(rewritten.contains("Java Agent"));
        assertTrue(rewritten.contains("这个怎么做"));
    }

    @Test
    void keepsStandaloneQuestionUnchanged() {
        String question = "请比较 PostgreSQL pgvector 与 Elasticsearch 的混合检索方案";

        assertEquals(question, rewriter.rewrite(question, List.of(
                new ConversationQueryRewriter.Turn("user", "之前讨论过别的话题")
        )));
    }
}
