package com.example.demo.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagSearchResultTest {

    @Test
    void boundsEvidenceContextWhileKeepingRankedFirstChunk() {
        RagChunkEvidence first = evidence("S1", "第一条证据 ".repeat(80));
        RagChunkEvidence second = evidence("S2", "第二条证据 ".repeat(80));
        RagSearchResult result = new RagSearchResult(
                "问题", List.of(first, second), 0.8, "高", "证据充分");

        String context = result.buildPromptContext(1100);

        assertTrue(context.length() <= 1100);
        assertTrue(context.contains("[S1]"));
    }

    private RagChunkEvidence evidence(String citationId, String text) {
        return new RagChunkEvidence(
                1L, text, 0, 1, "章节", "TEXT", null,
                citationId, "document", 0.9, 0.9,
                0.9, 0.8, 1, 1);
    }
}
