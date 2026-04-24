package com.codepilot.core.application.review;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.llm.LlmMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LoopDetector {

    private final int repeatThreshold;

    private final LoopJudge loopJudge;

    public LoopDetector() {
        this(3);
    }

    public LoopDetector(int repeatThreshold) {
        this(repeatThreshold, null);
    }

    public LoopDetector(int repeatThreshold, LoopJudge loopJudge) {
        if (repeatThreshold < 2) {
            throw new DomainRuleViolationException("LoopDetector repeatThreshold must be at least 2");
        }
        this.repeatThreshold = repeatThreshold;
        this.loopJudge = loopJudge;
    }

    public Detection detect(List<LlmMessage> messages) {
        List<List<String>> toolCallRounds = extractToolCallRounds(messages);
        if (toolCallRounds.size() < repeatThreshold) {
            return Detection.clear();
        }

        List<String> lastRound = toolCallRounds.get(toolCallRounds.size() - 1);
        if (lastRound.isEmpty()) {
            return Detection.clear();
        }

        for (int index = toolCallRounds.size() - repeatThreshold; index < toolCallRounds.size(); index++) {
            if (!toolCallRounds.get(index).equals(lastRound)) {
                return Detection.clear();
            }
        }

        // Repeating the same tool signature across consecutive turns means the agent is spending budget without adding evidence.
        Pattern pattern = new Pattern(lastRound, repeatThreshold);
        String reason = "Detected repeated tool call pattern across %d consecutive turns: %s"
                .formatted(repeatThreshold, pattern.signatures());
        if (loopJudge == null) {
            return Detection.loopDetected(reason, false);
        }
        return loopJudge.confirm(messages == null ? List.of() : List.copyOf(messages), pattern)
                ? Detection.loopDetected(reason, true)
                : Detection.clear();
    }

    private List<List<String>> extractToolCallRounds(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<List<String>> rounds = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (!"assistant".equals(message.role()) || message.content() == null || !message.content().contains("decision=CALL_TOOL")) {
                continue;
            }
            List<String> signatures = new ArrayList<>();
            for (String line : message.content().split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("signature=")) {
                    signatures.add(trimmed.substring("signature=".length()));
                }
            }
            if (!signatures.isEmpty()) {
                rounds.add(List.copyOf(signatures));
            }
        }
        return List.copyOf(rounds);
    }

    @FunctionalInterface
    public interface LoopJudge {

        boolean confirm(List<LlmMessage> messages, Pattern pattern);
    }

    public record Pattern(
            List<String> signatures,
            int repeatThreshold
    ) {

        public Pattern {
            signatures = signatures == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(signatures));
        }
    }

    public record Detection(
            boolean loopDetected,
            String reason,
            boolean confirmedByJudge
    ) {

        private static Detection clear() {
            return new Detection(false, "", false);
        }

        private static Detection loopDetected(String reason, boolean confirmedByJudge) {
            return new Detection(true, reason == null ? "" : reason, confirmedByJudge);
        }
    }
}
