package com.codepilot.core.domain.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record LlmChunk(
        String contentDelta,
        List<ToolCallInResponse> toolCalls,
        LlmUsage usage,
        String finishReason
) {

    public LlmChunk {
        toolCalls = toolCalls == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    }
}
