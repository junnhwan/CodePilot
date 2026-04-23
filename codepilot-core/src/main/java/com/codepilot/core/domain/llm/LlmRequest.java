package com.codepilot.core.domain.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        List<ToolDefinition> tools,
        Map<String, Object> params
) {

    public LlmRequest {
        messages = messages == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        tools = tools == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(tools));
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public static LlmRequest of(String model, List<LlmMessage> messages) {
        return new LlmRequest(model, messages, List.of(), Map.of());
    }
}
