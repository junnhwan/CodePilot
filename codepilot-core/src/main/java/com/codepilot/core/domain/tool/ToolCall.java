package com.codepilot.core.domain.tool;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public record ToolCall(
        String callId,
        String toolName,
        Map<String, Object> arguments
) {

    public ToolCall {
        callId = requireText(callId, "callId");
        toolName = requireText(toolName, "toolName");
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public String signature() {
        return toolName + ":" + new TreeMap<>(arguments);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
