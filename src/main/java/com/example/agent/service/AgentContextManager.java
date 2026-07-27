package com.example.agent.service;

import com.example.agent.entity.AgentMessage;
import com.example.agent.entity.AgentMemory;
import com.example.agent.entity.AgentSession;
import com.example.agent.entity.AgentStep;
import com.example.agent.entity.AgentStepStatus;
import com.example.agent.entity.AgentStepType;
import com.example.agent.repository.AgentStepRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds a bounded context window for every Agent request.
 *
 * The manager keeps the raw transcript as the source of truth, but chooses
 * what the model sees using a character-based token approximation, durable
 * memories and relevant historical tool observations.
 */
@Service
public class AgentContextManager {

    private static final int APPROX_CHARS_PER_TOKEN = 4;
    private static final int MAX_SINGLE_MESSAGE_CHARS = 2400;

    private final ChatModel chatModel;
    private final AgentSessionService sessionService;
    private final AgentMemoryService memoryService;
    private final AgentStepRepository stepRepository;
    private final ConversationQueryRewriter queryRewriter;
    private final int maxContextTokens;
    private final int recentMessageTokens;
    private final int relevantMessageTokens;
    private final int relevantExecutionTokens;
    private final int memoryTokens;
    private final int summaryMaxChars;
    private final int summaryTriggerMessageCount;
    private final int summaryTriggerTokens;

    public AgentContextManager(@Qualifier("agentDeepSeekChatModel") ChatModel chatModel,
                               AgentSessionService sessionService,
                               AgentMemoryService memoryService,
                               AgentStepRepository stepRepository,
                               ConversationQueryRewriter queryRewriter,
                               @Value("${agent.context.max-tokens:6000}") int maxContextTokens,
                               @Value("${agent.context.recent-message-tokens:2200}") int recentMessageTokens,
                               @Value("${agent.context.relevant-message-tokens:900}") int relevantMessageTokens,
                               @Value("${agent.context.relevant-execution-tokens:1000}") int relevantExecutionTokens,
                               @Value("${agent.context.memory-tokens:1000}") int memoryTokens,
                               @Value("${agent.history.summary-max-chars:3000}") int summaryMaxChars,
                               @Value("${agent.history.summary-trigger-message-count:6}") int summaryTriggerMessageCount,
                               @Value("${agent.history.summary-trigger-tokens:1500}") int summaryTriggerTokens) {
        this.chatModel = chatModel;
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.stepRepository = stepRepository;
        this.queryRewriter = queryRewriter;
        this.maxContextTokens = Math.max(2000, maxContextTokens);
        this.recentMessageTokens = Math.max(400, recentMessageTokens);
        this.relevantMessageTokens = Math.max(200, relevantMessageTokens);
        this.relevantExecutionTokens = Math.max(200, relevantExecutionTokens);
        this.memoryTokens = Math.max(200, memoryTokens);
        this.summaryMaxChars = Math.max(500, summaryMaxChars);
        this.summaryTriggerMessageCount = Math.max(2, summaryTriggerMessageCount);
        this.summaryTriggerTokens = Math.max(400, summaryTriggerTokens);
    }

    public ContextPack build(AgentSession session,
                             Long currentUserMessageId,
                             String question) {
        List<AgentMessage> allMessages = sessionService.listMessages(session.getId());
        List<AgentMessage> previousMessages = allMessages.stream()
                .filter(message -> message.getId() == null || !message.getId().equals(currentUserMessageId))
                .toList();

        List<AgentMessage> recentMessages = selectRecent(previousMessages, recentMessageTokens * APPROX_CHARS_PER_TOKEN);
        Set<Long> recentIds = recentMessages.stream()
                .map(AgentMessage::getId)
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.toSet());
        List<AgentMessage> olderMessages = previousMessages.stream()
                .filter(message -> message.getId() == null || !recentIds.contains(message.getId()))
                .toList();

        AgentSession currentSession = updateConversationSummary(session, olderMessages);

        String contextualQuestion = queryRewriter.rewrite(
                question,
                recentMessages.stream()
                        .map(message -> new ConversationQueryRewriter.Turn(message.getRole(), message.getContent()))
                        .toList());
        List<AgentMemory> memories = memoryService.retrieve(
                currentSession.getUserId(), currentSession.getId(), contextualQuestion, 8);
        List<AgentMessage> relevantMessages = selectRelevantMessages(
                olderMessages, contextualQuestion, relevantMessageTokens * APPROX_CHARS_PER_TOKEN);
        List<AgentStep> relevantSteps = selectRelevantSteps(
                currentSession.getId(), contextualQuestion, relevantExecutionTokens * APPROX_CHARS_PER_TOKEN);

        List<Message> history = new ArrayList<>();
        int remainingChars = maxContextTokens * APPROX_CHARS_PER_TOKEN;
        remainingChars = appendSystemBlock(history, "Earlier conversation memory", currentSession.getConversationSummary(),
                remainingChars, summaryMaxChars);
        remainingChars = appendSystemBlock(history, "Relevant durable memory", formatMemories(memories),
                remainingChars, memoryTokens * APPROX_CHARS_PER_TOKEN);
        remainingChars = appendSystemBlock(history, "Relevant earlier conversation", formatMessages(relevantMessages),
                remainingChars, relevantMessageTokens * APPROX_CHARS_PER_TOKEN);
        remainingChars = appendSystemBlock(history, "Relevant previous tool observations", formatSteps(relevantSteps),
                remainingChars, relevantExecutionTokens * APPROX_CHARS_PER_TOKEN);
        remainingChars = appendRecentMessages(history, recentMessages, remainingChars);

        int estimatedTokens = Math.max(1, (maxContextTokens * APPROX_CHARS_PER_TOKEN - remainingChars)
                / APPROX_CHARS_PER_TOKEN);
        return new ContextPack(List.copyOf(history), contextualQuestion, estimatedTokens);
    }

    private AgentSession updateConversationSummary(AgentSession session,
                                                    List<AgentMessage> olderMessages) {
        if (olderMessages.isEmpty()) {
            return session;
        }
        Long lastSummarizedMessageId = session.getSummarizedMessageId();
        List<AgentMessage> unsummarized = olderMessages.stream()
                .filter(message -> message.getId() != null)
                .filter(message -> lastSummarizedMessageId == null || message.getId() > lastSummarizedMessageId)
                .toList();
        if (unsummarized.isEmpty()) {
            return session;
        }
        if (!shouldSummarize(unsummarized)) {
            return session;
        }
        try {
            String summary = summarizeHistory(session.getConversationSummary(), unsummarized);
            Long summarizedMessageId = unsummarized.get(unsummarized.size() - 1).getId();
            return sessionService.updateSummary(session.getId(), summary, summarizedMessageId);
        } catch (RuntimeException ignored) {
            // A summary failure must not remove the recent-message fallback.
            return session;
        }
    }

    /**
     * Use a high-water trigger for summaries. Once a batch is summarized,
     * summarizedMessageId advances to the end of the batch, so the pending
     * amount drops back to zero and the next request will not summarize again
     * until enough new historical context accumulates.
     */
    private boolean shouldSummarize(List<AgentMessage> unsummarized) {
        if (unsummarized.size() >= summaryTriggerMessageCount) {
            return true;
        }
        int pendingChars = unsummarized.stream()
                .mapToInt(message -> Math.min(MAX_SINGLE_MESSAGE_CHARS, content(message).length()) + 32)
                .sum();
        int pendingTokens = (pendingChars + APPROX_CHARS_PER_TOKEN - 1) / APPROX_CHARS_PER_TOKEN;
        return pendingTokens >= summaryTriggerTokens;
    }

    private List<AgentMessage> selectRecent(List<AgentMessage> messages, int budgetChars) {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<AgentMessage> selected = new ArrayList<>();
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage message = messages.get(i);
            int cost = Math.min(MAX_SINGLE_MESSAGE_CHARS, content(message).length()) + 32;
            if (!selected.isEmpty() && used + cost > budgetChars) {
                break;
            }
            selected.add(message);
            used += cost;
        }
        java.util.Collections.reverse(selected);
        return selected;
    }

    private List<AgentMessage> selectRelevantMessages(List<AgentMessage> messages,
                                                       String question,
                                                       int budgetChars) {
        Set<String> queryTerms = terms(question);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<ScoredMessage> scored = messages.stream()
                .map(message -> new ScoredMessage(message, lexicalScore(queryTerms, terms(content(message)))))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredMessage::score).reversed()
                        .thenComparing(item -> item.message().getId() == null ? 0L : item.message().getId(), Comparator.reverseOrder()))
                .toList();

        List<AgentMessage> result = new ArrayList<>();
        int used = 0;
        for (ScoredMessage item : scored) {
            int cost = Math.min(MAX_SINGLE_MESSAGE_CHARS, content(item.message()).length()) + 32;
            if (!result.isEmpty() && used + cost > budgetChars) {
                break;
            }
            result.add(item.message());
            used += cost;
        }
        return result;
    }

    private List<AgentStep> selectRelevantSteps(Long sessionId, String question, int budgetChars) {
        Set<String> queryTerms = terms(question);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<AgentStep> candidates = stepRepository.findBySessionIdOrderByStepIndexAscIdAsc(sessionId).stream()
                .filter(step -> step.getStepType() == AgentStepType.TOOL_RESULT)
                .filter(step -> step.getStatus() == AgentStepStatus.SUCCESS)
                .filter(step -> !content(step.getToolResult()).isBlank())
                .sorted(Comparator.comparing(AgentStep::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();

        List<ScoredStep> scored = candidates.stream()
                .map(step -> new ScoredStep(step, lexicalScore(queryTerms,
                        terms(content(step.getToolName()) + " " + content(step.getToolResult())))))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredStep::score).reversed())
                .toList();

        List<AgentStep> result = new ArrayList<>();
        int used = 0;
        for (ScoredStep item : scored) {
            int cost = Math.min(1600, content(item.step().getToolResult()).length()) + 64;
            if (!result.isEmpty() && used + cost > budgetChars) {
                break;
            }
            result.add(item.step());
            used += cost;
        }
        return result;
    }

    private int appendSystemBlock(List<Message> history,
                                  String title,
                                  String content,
                                  int remainingChars,
                                  int blockBudgetChars) {
        if (content == null || content.isBlank() || remainingChars < 120) {
            return remainingChars;
        }
        int allowed = Math.min(remainingChars, Math.max(120, blockBudgetChars));
        String block = title + ":\n" + truncate(content, allowed - title.length() - 2);
        history.add(new SystemMessage(block));
        return Math.max(0, remainingChars - block.length());
    }

    private int appendRecentMessages(List<Message> history,
                                     List<AgentMessage> recentMessages,
                                     int remainingChars) {
        for (AgentMessage message : recentMessages) {
            if (remainingChars < 120) {
                break;
            }
            String text = truncate(content(message), Math.min(MAX_SINGLE_MESSAGE_CHARS, remainingChars - 32));
            Message springMessage = "assistant".equalsIgnoreCase(message.getRole())
                    ? new AssistantMessage(text)
                    : new UserMessage(text);
            history.add(springMessage);
            remainingChars = Math.max(0, remainingChars - text.length() - 32);
        }
        return remainingChars;
    }

    private String formatMemories(List<AgentMemory> memories) {
        return memories.stream()
                .map(memory -> "- [" + memory.getMemoryType() + "] " + memory.getContent()
                        + " (confidence=" + String.format(Locale.ROOT, "%.2f", memory.getConfidence() == null ? 0.0 : memory.getConfidence()) + ")")
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String formatMessages(List<AgentMessage> messages) {
        return messages.stream()
                .map(message -> ("assistant".equalsIgnoreCase(message.getRole()) ? "Assistant: " : "User: ")
                        + truncate(content(message), MAX_SINGLE_MESSAGE_CHARS))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String formatSteps(List<AgentStep> steps) {
        return steps.stream()
                .map(step -> "- tool=" + content(step.getToolName()) + ", result="
                        + truncate(content(step.getToolResult()), 1600))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String summarizeHistory(String existingSummary, List<AgentMessage> messagesToSummarize) {
        String transcript = messagesToSummarize.stream()
                .map(message -> ("assistant".equalsIgnoreCase(message.getRole()) ? "Assistant: " : "User: ")
                        + truncate(content(message), 2400))
                .collect(java.util.stream.Collectors.joining("\n"));
        String summary = ChatClient.create(chatModel)
                .prompt()
                .system("""
                        You maintain compact long-term memory for a Chinese AI agent conversation.
                        Merge existing memory and new transcript into one concise Chinese memory note.
                        Preserve stable user preferences, project facts, decisions, constraints,
                        unresolved tasks and useful technical context. Remove chit-chat and obsolete details.
                        Do not mention that this is a summary.
                        """)
                .user("""
                        Existing memory:
                        %s

                        New transcript:
                        %s

                        Write the updated memory within %d Chinese characters.
                        """.formatted(content(existingSummary), transcript, summaryMaxChars))
                .call()
                .content();
        return truncate(content(summary), summaryMaxChars);
    }

    private double lexicalScore(Set<String> queryTerms, Set<String> candidateTerms) {
        long overlap = candidateTerms.stream().filter(queryTerms::contains).count();
        return queryTerms.isEmpty() ? 0.0 : (double) overlap / queryTerms.size();
    }

    private Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        StringBuilder ascii = new StringBuilder();
        String safe = content(text);
        for (int offset = 0; offset < safe.length();) {
            int codePoint = safe.codePointAt(offset);
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

    private String content(String value) {
        return value == null ? "" : value.trim();
    }

    private String content(AgentMessage message) {
        return message == null ? "" : content(message.getContent());
    }

    private String truncate(String value, int maxChars) {
        String safe = content(value);
        if (safe.length() <= Math.max(1, maxChars)) {
            return safe;
        }
        return safe.substring(0, Math.max(1, maxChars)) + "…";
    }

    public record ContextPack(List<Message> history, String contextualQuestion, int estimatedTokens) {
    }

    private record ScoredMessage(AgentMessage message, double score) {
    }

    private record ScoredStep(AgentStep step, double score) {
    }
}
