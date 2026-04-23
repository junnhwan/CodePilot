package com.codepilot.core.application.tool;

import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

public final class ToolExecutor {

    private final ToolRegistry toolRegistry;

    public ToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public List<ToolResult> executeAll(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        List<ToolResult> results = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            var tool = toolRegistry.getRequired(toolCall.toolName());
            try {
                ToolResult toolResult = tool.execute(toolCall);
                if (toolResult == null) {
                    throw new IllegalStateException("Tool returned null result");
                }
                results.add(toolResult);
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                        "Tool execution failed for tool=%s callId=%s".formatted(toolCall.toolName(), toolCall.callId()),
                        error
                );
            }
        }
        return List.copyOf(results);
    }
}
