package com.example.demo.service;

import java.util.List;
import java.util.Locale;

public class RagSearchResult {

    private static final int DEFAULT_PROMPT_CONTEXT_CHARS = 12000;

    private final String question;
    private final List<RagChunkEvidence> evidence;
    private final double confidenceScore;
    private final String confidenceLevel;
    private final String confidenceReason;

    public RagSearchResult(String question,
                           List<RagChunkEvidence> evidence,
                           double confidenceScore,
                           String confidenceLevel,
                           String confidenceReason) {
        this.question = question;
        this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        this.confidenceScore = confidenceScore;
        this.confidenceLevel = confidenceLevel;
        this.confidenceReason = confidenceReason;
    }

    public String getQuestion() {
        return question;
    }

    public List<RagChunkEvidence> getEvidence() {
        return evidence;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public String getConfidenceLevel() {
        return confidenceLevel;
    }

    public String getConfidenceReason() {
        return confidenceReason;
    }

    public boolean isEmpty() {
        return evidence.isEmpty();
    }

    public String buildPromptContext() {
        return buildPromptContext(DEFAULT_PROMPT_CONTEXT_CHARS);
    }

    /**
     * Builds bounded evidence for the final prompt. Retrieval can return many
     * chunks, but sending all of them makes the answer less reliable as well
     * as more expensive. Chunks stay in rank order and the tail is discarded
     * only after the budget is reached.
     */
    public String buildPromptContext(int maxChars) {
        if (evidence.isEmpty()) {
            return "(No retrieved context)";
        }
        int safeMaxChars = Math.max(1000, maxChars);
        StringBuilder builder = new StringBuilder();
        int omitted = 0;
        for (int index = 0; index < evidence.size(); index++) {
            RagChunkEvidence item = evidence.get(index);
            String entry = new StringBuilder("[")
                    .append(item.getCitationId())
                    .append("] chunkId=")
                    .append(item.getId())
                    .append(", chunkIndex=")
                    .append(item.getChunkIndex() == null ? "unknown" : item.getChunkIndex())
                    .append(", page=")
                    .append(item.getPageNumber() == null ? "unknown" : item.getPageNumber())
                    .append(", type=")
                    .append(blankToDefault(item.getContentType(), "TEXT"))
                    .append(", section=")
                    .append(blankToDefault(item.getSectionTitle(), "-"))
                    .append(", source=")
                    .append(item.getSourceType())
                    .append(", relevance=")
                    .append(format(item.getConfidence()))
                    .append("\n")
                    .append(item.getText() == null ? "" : item.getText())
                    .append("\n\n")
                    .toString();
            if (builder.length() + entry.length() <= safeMaxChars) {
                builder.append(entry);
                continue;
            }

            int remaining = safeMaxChars - builder.length();
            if (remaining > 0) {
                builder.append(truncate(entry, remaining));
            }
            omitted = evidence.size() - index - 1;
            break;
        }
        if (omitted > 0) {
            String omittedNotice = "\n[其余 " + omitted + " 条证据因上下文预算未注入。]";
            if (builder.length() + omittedNotice.length() <= safeMaxChars) {
                builder.append(omittedNotice);
            }
        }
        return builder.toString();
    }

    public String buildCitationSummary() {
        if (evidence.isEmpty()) {
            return "\n\n**答案置信度**\n- 置信度：低 (0.00)\n- 说明：没有检索到可用证据。\n";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n**答案置信度**\n")
                .append("- 置信度：")
                .append(confidenceLevel)
                .append(" (")
                .append(format(confidenceScore))
                .append(")\n")
                .append("- 说明：")
                .append(confidenceReason)
                .append("\n\n")
                .append("**引用来源**\n");
        for (RagChunkEvidence item : evidence) {
            builder.append("- [")
                    .append(item.getCitationId())
                    .append("] chunkId=")
                    .append(item.getId())
                    .append(", chunkIndex=")
                    .append(item.getChunkIndex() == null ? "unknown" : item.getChunkIndex())
                    .append(", page=")
                    .append(item.getPageNumber() == null ? "unknown" : item.getPageNumber())
                    .append(", type=")
                    .append(blankToDefault(item.getContentType(), "TEXT"))
                    .append(", section=")
                    .append(blankToDefault(item.getSectionTitle(), "-"))
                    .append(", source=")
                    .append(item.getSourceType())
                    .append(", relevance=")
                    .append(format(item.getConfidence()))
                    .append("\n");
            if (item.getSourcePath() != null && !item.getSourcePath().isBlank()) {
                builder.append("  - sourcePath: ").append(item.getSourcePath()).append("\n");
                if (item.getSourcePath().startsWith("/uploads/")) {
                    builder.append("  - ![source](").append(item.getSourcePath()).append(")\n");
                }
            }
        }
        return builder.toString();
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 1) {
            return "…";
        }
        return value.substring(0, maxChars - 1) + "…";
    }
}
