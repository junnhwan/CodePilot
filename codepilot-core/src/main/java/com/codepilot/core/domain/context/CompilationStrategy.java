package com.codepilot.core.domain.context;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CompilationStrategy(
        String profileId,
        String language,
        String framework,
        String buildTool,
        String astParser,
        TokenBudget tokenBudget,
        List<String> filePriority,
        Map<String, AstMode> astModes,
        FallbackStrategy fallbackStrategy,
        List<String> excludePatterns
) {

    public CompilationStrategy {
        profileId = requireText(profileId, "profileId");
        language = requireText(language, "language");
        framework = requireText(framework, "framework");
        buildTool = requireText(buildTool, "buildTool");
        astParser = requireText(astParser, "astParser");
        if (tokenBudget == null) {
            throw new DomainRuleViolationException("CompilationStrategy[%s] tokenBudget must not be null"
                    .formatted(profileId));
        }
        filePriority = filePriority == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(filePriority));
        astModes = astModes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(astModes));
        if (fallbackStrategy == null) {
            throw new DomainRuleViolationException("CompilationStrategy[%s] fallbackStrategy must not be null"
                    .formatted(profileId));
        }
        excludePatterns = excludePatterns == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(excludePatterns));
    }

    public AstMode astModeFor(String priorityBucket) {
        return astModes.getOrDefault(priorityBucket, AstMode.SYMBOLS);
    }

    public record TokenBudget(
            int total,
            int structuredFacts,
            int diffSummary,
            int impactSet,
            int codeSnippets,
            int memories,
            int reserve
    ) {

        public TokenBudget {
            if (total <= 0 || structuredFacts < 0 || diffSummary < 0 || impactSet < 0
                    || codeSnippets < 0 || memories < 0 || reserve < 0) {
                throw new DomainRuleViolationException("CompilationStrategy.TokenBudget values must be non-negative and total must be positive");
            }
            if (reserve >= total) {
                throw new DomainRuleViolationException("CompilationStrategy.TokenBudget reserve must be smaller than total");
            }
        }
    }

    public enum AstMode {
        FULL,
        METHOD_SIG,
        SYMBOLS,
        REGEX_TEXT
    }

    public enum FallbackStrategy {
        REGEX_TEXT_ANALYSIS
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
