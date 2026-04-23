package com.codepilot.core.domain.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolDefinition(String name, String description, Map<String, Object> parameters) {

    public ToolDefinition {
        parameters = parameters == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
