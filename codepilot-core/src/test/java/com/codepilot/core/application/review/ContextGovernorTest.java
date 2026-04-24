package com.codepilot.core.application.review;

import com.codepilot.core.domain.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextGovernorTest {

    private final TokenCounter tokenCounter = new TokenCounter();

    private final ContextGovernor governor = new ContextGovernor(tokenCounter);

    @Test
    void microcompactsOlderToolResultsBeforeSnippingHistory() {
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", "system prompt"),
                new LlmMessage("user", "user prompt"),
                toolCallMessage("call-1", "read_file", "{file_path=src/main/java/com/example/LargeFile.java}"),
                toolResultMessage("read_file", "call-1", true, "A".repeat(900)),
                toolCallMessage("call-2", "search_pattern", "{pattern=jdbcTemplate.query}"),
                toolResultMessage("search_pattern", "call-2", true, "jdbcTemplate.query")
        );

        int budget = tokenCounter.countMessages(messages) - 120;

        ContextGovernor.CompactionResult result = governor.compact(messages, budget);

        assertThat(result.withinBudget()).isTrue();
        assertThat(result.appliedStrategies()).contains(ContextGovernor.Strategy.MICROCOMPACT);
        assertThat(result.appliedStrategies()).doesNotContain(ContextGovernor.Strategy.HISTORY_SNIP);
        assertThat(result.messages()).hasSize(messages.size());
        assertThat(result.messages().get(3).content()).contains("summary=");
        assertThat(result.messages().get(5).content()).contains("jdbcTemplate.query");
    }

    @Test
    void historySnippingRemovesOldestRoundsOnMessageBoundaries() {
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", "system prompt"),
                new LlmMessage("user", "user prompt"),
                toolCallMessage("call-1", "read_file", "{file_path=src/main/java/com/example/OldOne.java}"),
                toolResultMessage("read_file", "call-1", true, "A".repeat(400)),
                toolCallMessage("call-2", "read_file", "{file_path=src/main/java/com/example/OldTwo.java}"),
                toolResultMessage("read_file", "call-2", true, "B".repeat(320)),
                toolCallMessage("call-3", "read_file", "{file_path=src/main/java/com/example/Latest.java}"),
                toolResultMessage("read_file", "call-3", true, "latest")
        );

        int budget = tokenCounter.countMessages(List.of(
                messages.get(0),
                messages.get(1),
                messages.get(6),
                messages.get(7)
        )) + 8;

        ContextGovernor.CompactionResult result = governor.compact(messages, budget);

        assertThat(result.withinBudget()).isTrue();
        assertThat(result.appliedStrategies()).contains(ContextGovernor.Strategy.HISTORY_SNIP);
        assertThat(result.messages()).extracting(LlmMessage::role)
                .containsExactly("system", "user", "assistant", "tool");
        assertThat(result.messages().get(2).content()).contains("call-3");
        assertThat(result.messages().get(3).content()).contains("latest");
    }

    @Test
    void orphanCleanupDropsDanglingToolFragments() {
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", "system prompt"),
                new LlmMessage("user", "user prompt"),
                toolResultMessage("read_file", "orphan-call", true, "stale"),
                toolCallMessage("call-no-result", "read_file", "{file_path=src/main/java/com/example/Missing.java}"),
                toolCallMessage("call-valid", "read_file", "{file_path=src/main/java/com/example/Valid.java}"),
                toolResultMessage("read_file", "call-valid", true, "valid result")
        );

        int budget = tokenCounter.countMessages(List.of(
                messages.get(0),
                messages.get(1),
                messages.get(4),
                messages.get(5)
        )) + 8;

        ContextGovernor.CompactionResult result = governor.compact(messages, budget);

        assertThat(result.messages()).extracting(LlmMessage::role)
                .containsExactly("system", "user", "assistant", "tool");
        assertThat(result.messages().get(2).content()).contains("call-valid");
        assertThat(result.messages().get(3).content()).contains("valid result");
    }

    private static LlmMessage toolCallMessage(String callId, String toolName, String arguments) {
        return new LlmMessage("assistant", """
                decision=CALL_TOOL
                call_id=%s
                signature=%s:%s
                tool=%s
                arguments=%s
                """.formatted(callId, toolName, arguments, toolName, arguments));
    }

    private static LlmMessage toolResultMessage(String toolName, String callId, boolean success, String output) {
        return new LlmMessage("tool", """
                tool=%s
                call_id=%s
                success=%s
                output:
                %s
                """.formatted(toolName, callId, success, output));
    }
}
