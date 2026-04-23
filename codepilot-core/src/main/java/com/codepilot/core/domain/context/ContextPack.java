package com.codepilot.core.domain.context;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.memory.ProjectMemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ContextPack(
        Map<String, String> structuredFacts,
        DiffSummary diffSummary,
        ImpactSet impactSet,
        List<CodeSnippet> snippets,
        ProjectMemory projectMemory,
        TokenBudget tokenBudget
) {

    public ContextPack {
        structuredFacts = structuredFacts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(structuredFacts));
        if (diffSummary == null) {
            throw new DomainRuleViolationException("ContextPack diffSummary must not be null");
        }
        if (impactSet == null) {
            throw new DomainRuleViolationException("ContextPack impactSet must not be null");
        }
        snippets = snippets == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(snippets));
        if (projectMemory == null) {
            throw new DomainRuleViolationException("ContextPack projectMemory must not be null");
        }
        if (tokenBudget == null) {
            throw new DomainRuleViolationException("ContextPack tokenBudget must not be null");
        }
    }

    public record CodeSnippet(
            String filePath,
            int startLine,
            int endLine,
            String content,
            String reason
    ) {

        public CodeSnippet {
            filePath = requireText(filePath, "filePath");
            if (startLine <= 0 || endLine < startLine) {
                throw new DomainRuleViolationException("CodeSnippet[%s] has invalid line range %d-%d"
                        .formatted(filePath, startLine, endLine));
            }
            content = content == null ? "" : content;
            reason = reason == null ? "" : reason;
        }
    }

    public record TokenBudget(
            int totalTokens,
            int reservedTokens,
            int usedTokens
    ) {

        public TokenBudget {
            if (totalTokens < 0 || reservedTokens < 0 || usedTokens < 0) {
                throw new DomainRuleViolationException("TokenBudget values must not be negative");
            }
            if (usedTokens > totalTokens) {
                throw new DomainRuleViolationException("TokenBudget usedTokens must not exceed totalTokens");
            }
        }

        public int remainingTokens() {
            return Math.max(totalTokens - usedTokens - reservedTokens, 0);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
