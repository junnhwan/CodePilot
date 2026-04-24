package com.codepilot.core.application.review;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.llm.LlmMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ContextGovernor {

    private static final int DEFAULT_PREVIEW_CHARS = 180;

    private final TokenCounter tokenCounter;

    private final int previewChars;

    public ContextGovernor(TokenCounter tokenCounter) {
        this(tokenCounter, DEFAULT_PREVIEW_CHARS);
    }

    ContextGovernor(TokenCounter tokenCounter, int previewChars) {
        if (tokenCounter == null) {
            throw new DomainRuleViolationException("ContextGovernor tokenCounter must not be null");
        }
        if (previewChars <= 0) {
            throw new DomainRuleViolationException("ContextGovernor previewChars must be positive");
        }
        this.tokenCounter = tokenCounter;
        this.previewChars = previewChars;
    }

    public CompactionResult compact(List<LlmMessage> messages, int budget) {
        if (budget < 0) {
            throw new DomainRuleViolationException("ContextGovernor budget must not be negative");
        }

        List<LlmMessage> current = messages == null ? List.of() : List.copyOf(messages);
        int originalTokens = tokenCounter.countMessages(current);
        List<Strategy> appliedStrategies = new ArrayList<>();

        if (originalTokens > budget) {
            List<LlmMessage> microcompacted = microcompact(current);
            if (!microcompacted.equals(current)) {
                current = microcompacted;
                appliedStrategies.add(Strategy.MICROCOMPACT);
            }
            if (tokenCounter.countMessages(current) > budget) {
                List<LlmMessage> snipped = historySnip(current, budget);
                if (!snipped.equals(current)) {
                    current = snipped;
                    appliedStrategies.add(Strategy.HISTORY_SNIP);
                }
            }
            if (tokenCounter.countMessages(current) > budget) {
                // If the newest round still does not fit after trimming history, compact it before we force a partial result.
                List<LlmMessage> compactedAgain = microcompact(current);
                if (!compactedAgain.equals(current) && !appliedStrategies.contains(Strategy.MICROCOMPACT)) {
                    appliedStrategies.add(Strategy.MICROCOMPACT);
                }
                current = compactedAgain;
            }
        }

        List<LlmMessage> cleaned = orphanCleanup(current);
        if (!cleaned.equals(current)) {
            current = cleaned;
            appliedStrategies.add(Strategy.ORPHAN_CLEANUP);
        }

        int compactedTokens = tokenCounter.countMessages(current);
        return new CompactionResult(
                current,
                originalTokens,
                compactedTokens,
                compactedTokens <= budget,
                appliedStrategies
        );
    }

    private List<LlmMessage> microcompact(List<LlmMessage> messages) {
        int latestToolCallIndex = lastToolCallAssistantIndex(messages);
        boolean changed = false;
        List<LlmMessage> compacted = new ArrayList<>(messages.size());

        for (int index = 0; index < messages.size(); index++) {
            LlmMessage message = messages.get(index);
            if (!"tool".equals(message.role())) {
                compacted.add(message);
                continue;
            }

            boolean compactPreferred = latestToolCallIndex < 0 || index < latestToolCallIndex;
            if (!compactPreferred && latestToolCallIndex >= 0) {
                compacted.add(message);
                continue;
            }

            String summarizedContent = summarizeToolResult(message.content());
            if (!summarizedContent.equals(message.content())) {
                compacted.add(new LlmMessage(message.role(), summarizedContent));
                changed = true;
            } else {
                compacted.add(message);
            }
        }

        if (changed) {
            return List.copyOf(compacted);
        }

        List<LlmMessage> fallbackCompacted = new ArrayList<>(messages.size());
        boolean fallbackChanged = false;
        for (LlmMessage message : messages) {
            if (!"tool".equals(message.role())) {
                fallbackCompacted.add(message);
                continue;
            }
            String summarizedContent = summarizeToolResult(message.content());
            if (!summarizedContent.equals(message.content())) {
                fallbackCompacted.add(new LlmMessage(message.role(), summarizedContent));
                fallbackChanged = true;
            } else {
                fallbackCompacted.add(message);
            }
        }
        return fallbackChanged ? List.copyOf(fallbackCompacted) : messages;
    }

    private List<LlmMessage> historySnip(List<LlmMessage> messages, int budget) {
        if (messages.isEmpty()) {
            return messages;
        }

        int cursor = 0;
        List<LlmMessage> prefix = new ArrayList<>();
        while (cursor < messages.size()) {
            LlmMessage message = messages.get(cursor);
            if ("assistant".equals(message.role()) || "tool".equals(message.role())) {
                break;
            }
            prefix.add(message);
            cursor++;
        }

        List<List<LlmMessage>> rounds = new ArrayList<>();
        while (cursor < messages.size()) {
            List<LlmMessage> round = new ArrayList<>();
            round.add(messages.get(cursor));
            cursor++;
            while (cursor < messages.size() && "tool".equals(messages.get(cursor).role())) {
                round.add(messages.get(cursor));
                cursor++;
            }
            rounds.add(List.copyOf(round));
        }

        List<List<LlmMessage>> remainingRounds = new ArrayList<>(rounds);
        List<LlmMessage> rebuilt = rebuild(prefix, remainingRounds);
        while (tokenCounter.countMessages(rebuilt) > budget && remainingRounds.size() > 1) {
            remainingRounds.remove(0);
            rebuilt = rebuild(prefix, remainingRounds);
        }
        return rebuilt;
    }

    private List<LlmMessage> orphanCleanup(List<LlmMessage> messages) {
        List<LlmMessage> cleaned = new ArrayList<>(messages.size());
        int cursor = 0;
        while (cursor < messages.size()) {
            LlmMessage message = messages.get(cursor);
            if (!"assistant".equals(message.role()) && !"tool".equals(message.role())) {
                cleaned.add(message);
                cursor++;
                continue;
            }

            if ("tool".equals(message.role())) {
                cursor++;
                continue;
            }

            if (!isToolCallAssistant(message)) {
                cleaned.add(message);
                cursor++;
                continue;
            }

            List<LlmMessage> toolMessages = new ArrayList<>();
            int nextCursor = cursor + 1;
            while (nextCursor < messages.size() && "tool".equals(messages.get(nextCursor).role())) {
                toolMessages.add(messages.get(nextCursor));
                nextCursor++;
            }
            if (!toolMessages.isEmpty()) {
                cleaned.add(message);
                cleaned.addAll(toolMessages);
            }
            cursor = nextCursor;
        }
        return List.copyOf(cleaned);
    }

    private List<LlmMessage> rebuild(List<LlmMessage> prefix, List<List<LlmMessage>> rounds) {
        List<LlmMessage> rebuilt = new ArrayList<>(prefix);
        rounds.forEach(rebuilt::addAll);
        return List.copyOf(rebuilt);
    }

    private int lastToolCallAssistantIndex(List<LlmMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (isToolCallAssistant(messages.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean isToolCallAssistant(LlmMessage message) {
        return "assistant".equals(message.role()) && message.content() != null && message.content().contains("decision=CALL_TOOL");
    }

    private String summarizeToolResult(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int outputMarker = content.indexOf("output:");
        if (outputMarker < 0) {
            return content;
        }

        String output = content.substring(outputMarker + "output:".length()).trim();
        if (output.length() <= previewChars) {
            return content;
        }

        String header = content.substring(0, outputMarker).trim();
        String preview = output.replaceAll("\\s+", " ").trim();
        if (preview.length() > previewChars) {
            preview = preview.substring(0, previewChars) + "...";
        }
        return (header + System.lineSeparator()
                + "summary=compacted tool result; output_chars=" + output.length() + System.lineSeparator()
                + "preview=" + preview).trim();
    }

    public enum Strategy {
        MICROCOMPACT,
        HISTORY_SNIP,
        ORPHAN_CLEANUP
    }

    public record CompactionResult(
            List<LlmMessage> messages,
            int originalTokens,
            int compactedTokens,
            boolean withinBudget,
            List<Strategy> appliedStrategies
    ) {

        public CompactionResult {
            messages = messages == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(messages));
            appliedStrategies = appliedStrategies == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(appliedStrategies));
        }
    }
}
