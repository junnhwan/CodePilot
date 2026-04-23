package com.codepilot.core.domain.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolResult(
        String callId,
        boolean success,
        String output,
        Map<String, Object> metadata
) {

    public ToolResult {
        output = output == null ? "" : output;
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static ToolResult success(String callId, String output, Map<String, Object> metadata) {
        return new ToolResult(callId, true, output, metadata);
    }

    public static ToolResult failure(String callId, String output, Map<String, Object> metadata) {
        return new ToolResult(callId, false, output, metadata);
    }
}
