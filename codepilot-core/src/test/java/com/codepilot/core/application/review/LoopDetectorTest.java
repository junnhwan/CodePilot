package com.codepilot.core.application.review;

import com.codepilot.core.domain.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoopDetectorTest {

    @Test
    void detectsRepeatedToolCallPatternAcrossThreeTurns() {
        LoopDetector detector = new LoopDetector(3);
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", "system prompt"),
                new LlmMessage("user", "user prompt"),
                toolCallMessage("call-1", "read_file", "{file_path=src/main/java/com/example/UserRepository.java}"),
                toolResultMessage("read_file", "call-1", "first"),
                toolCallMessage("call-2", "read_file", "{file_path=src/main/java/com/example/UserRepository.java}"),
                toolResultMessage("read_file", "call-2", "second"),
                toolCallMessage("call-3", "read_file", "{file_path=src/main/java/com/example/UserRepository.java}"),
                toolResultMessage("read_file", "call-3", "third")
        );

        LoopDetector.Detection detection = detector.detect(messages);

        assertThat(detection.loopDetected()).isTrue();
        assertThat(detection.reason()).contains("read_file:{file_path=src/main/java/com/example/UserRepository.java}");
        assertThat(detection.confirmedByJudge()).isFalse();
    }

    @Test
    void ignoresNonRepeatingToolCallSequences() {
        LoopDetector detector = new LoopDetector(3);
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", "system prompt"),
                new LlmMessage("user", "user prompt"),
                toolCallMessage("call-1", "read_file", "{file_path=src/main/java/com/example/One.java}"),
                toolResultMessage("read_file", "call-1", "first"),
                toolCallMessage("call-2", "search_pattern", "{pattern=jdbcTemplate.query}"),
                toolResultMessage("search_pattern", "call-2", "second"),
                toolCallMessage("call-3", "read_file", "{file_path=src/main/java/com/example/One.java}"),
                toolResultMessage("read_file", "call-3", "third")
        );

        LoopDetector.Detection detection = detector.detect(messages);

        assertThat(detection.loopDetected()).isFalse();
        assertThat(detection.reason()).isBlank();
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

    private static LlmMessage toolResultMessage(String toolName, String callId, String output) {
        return new LlmMessage("tool", """
                tool=%s
                call_id=%s
                success=true
                output:
                %s
                """.formatted(toolName, callId, output));
    }
}
