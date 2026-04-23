package com.codepilot.core.domain.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolCallInResponse(String id, String name, Map<String, Object> arguments) {

    public ToolCallInResponse {
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
