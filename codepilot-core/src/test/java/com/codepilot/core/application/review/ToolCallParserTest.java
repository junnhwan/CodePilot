package com.codepilot.core.application.review;

import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.ToolCallInResponse;
import com.codepilot.core.domain.tool.ToolCall;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallParserTest {

    private final ToolCallParser parser = new ToolCallParser(JsonMapper.builder().findAndAddModules().build());

    @Test
    void parsesNativeFunctionCallingPayload() {
        LlmResponse response = new LlmResponse(
                "",
                List.of(new ToolCallInResponse(
                        "call-1",
                        "read_file",
                        Map.of("file_path", "src/main/java/com/example/DemoRepository.java")
                )),
                null,
                "tool_calls"
        );

        List<ToolCall> toolCalls = parser.parse(response);

        assertThat(toolCalls).containsExactly(new ToolCall(
                "call-1",
                "read_file",
                Map.of("file_path", "src/main/java/com/example/DemoRepository.java")
        ));
    }

    @Test
    void parsesPromptDrivenToolDecisionFromJsonFence() {
        LlmResponse response = new LlmResponse(
                """
                ```json
                {
                  "decision": "CALL_TOOL",
                  "tool_calls": [
                    {
                      "tool": "search_pattern",
                      "arguments": {
                        "pattern": "jdbcTemplate.query"
                      }
                    }
                  ]
                }
                ```
                """,
                List.of(),
                null,
                "stop"
        );

        List<ToolCall> toolCalls = parser.parse(response);

        assertThat(toolCalls).containsExactly(new ToolCall(
                "tool-call-1",
                "search_pattern",
                Map.of("pattern", "jdbcTemplate.query")
        ));
    }
}
