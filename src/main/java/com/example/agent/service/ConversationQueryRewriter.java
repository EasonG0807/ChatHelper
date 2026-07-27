package com.example.agent.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Turns short follow-up questions into retrieval-friendly contextual queries.
 * This is deterministic, so it adds no extra model call or network latency.
 */
@Service
public class ConversationQueryRewriter {

    public String rewrite(String question, List<Turn> turns) {
        if (question == null || question.isBlank() || turns == null || turns.isEmpty() || !needsRewrite(question)) {
            return question;
        }

        String previousUserRequests = turns.stream()
                .filter(turn -> "user".equalsIgnoreCase(turn.role()))
                .skip(Math.max(0, turns.stream()
                        .filter(turn -> "user".equalsIgnoreCase(turn.role()))
                        .count() - 2))
                .map(turn -> truncate(turn.content(), 600))
                .collect(Collectors.joining("\n"));
        if (previousUserRequests.isBlank()) {
            return question;
        }
        return "Relevant previous user requests:\n" + previousUserRequests
                + "\n\nCurrent user request:\n" + question
                + "\n\nResolve references such as this/that/it using the previous requests before retrieval.";
    }

    private boolean needsRewrite(String question) {
        String value = question.toLowerCase(Locale.ROOT);
        return question.length() <= 40
                || value.contains("它") || value.contains("这个") || value.contains("那个")
                || value.contains("上面") || value.contains("刚才") || value.contains("继续")
                || value.contains("为什么") || value.contains("怎么做") || value.contains("what about")
                || value.contains("why") || value.contains("it ");
    }

    private String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength) + "…";
    }

    public record Turn(String role, String content) {
    }
}
