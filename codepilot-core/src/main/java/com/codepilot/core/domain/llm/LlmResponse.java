package com.codepilot.core.domain.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record LlmResponse(
        String content,
        List<ToolCallInResponse> toolCalls,
        LlmUsage usage,
        String finishReason
) {

    public LlmResponse {
        toolCalls = toolCalls == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    }
}
