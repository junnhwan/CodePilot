package com.codepilot.core.application.review;

import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.ToolCallInResponse;
import com.codepilot.core.domain.tool.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ToolCallParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ToolCallParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ToolCall> parse(LlmResponse response) {
        if (response == null) {
            return List.of();
        }

        if (!response.toolCalls().isEmpty()) {
            return response.toolCalls().stream()
                    .map(this::toToolCall)
                    .toList();
        }

        JsonNode payload = parseStructuredContent(response.content());
        if (payload == null || !"CALL_TOOL".equalsIgnoreCase(payload.path("decision").asText())) {
            return List.of();
        }

        JsonNode toolCallsNode = payload.path("tool_calls");
        if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return List.of();
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        int index = 1;
        for (JsonNode toolCallNode : toolCallsNode) {
            String callId = toolCallNode.path("id").asText("");
            if (callId.isBlank()) {
                callId = "tool-call-" + index;
            }
            String toolName = toolCallNode.path("tool").asText(toolCallNode.path("name").asText(""));
            Map<String, Object> arguments = objectMapper.convertValue(toolCallNode.path("arguments"), MAP_TYPE);
            toolCalls.add(new ToolCall(callId, toolName, arguments));
            index++;
        }
        return List.copyOf(toolCalls);
    }

    public JsonNode parseStructuredContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }

        String normalized = stripMarkdownFence(rawContent.trim());
        try {
            return objectMapper.readTree(normalized);
        } catch (JsonProcessingException ignored) {
            int firstBrace = normalized.indexOf('{');
            int lastBrace = normalized.lastIndexOf('}');
            if (firstBrace < 0 || lastBrace <= firstBrace) {
                throw new IllegalArgumentException("LLM response does not contain valid JSON content");
            }
            try {
                return objectMapper.readTree(normalized.substring(firstBrace, lastBrace + 1));
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("Failed to parse structured LLM response", error);
            }
        }
    }

    private ToolCall toToolCall(ToolCallInResponse toolCall) {
        String callId = toolCall.id() == null || toolCall.id().isBlank() ? "tool-call-1" : toolCall.id();
        return new ToolCall(callId, toolCall.name(), toolCall.arguments());
    }

    private String stripMarkdownFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }

        int firstLineEnd = content.indexOf('\n');
        int lastFenceStart = content.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFenceStart <= firstLineEnd) {
            return content;
        }
        return content.substring(firstLineEnd + 1, lastFenceStart).trim();
    }
}
