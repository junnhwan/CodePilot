package com.codepilot.core.domain.llm;

public record LlmUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
