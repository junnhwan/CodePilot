package com.codepilot.core.domain.memory;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.time.Instant;

public record ReviewPattern(
        String patternId,
        String projectId,
        PatternType patternType,
        String title,
        String description,
        String codeExample,
        int frequency,
        Instant lastSeenAt
) {

    public ReviewPattern {
        patternId = requireText(patternId, "patternId");
        projectId = requireText(projectId, "projectId");
        if (patternType == null) {
            throw new DomainRuleViolationException("ReviewPattern[%s] patternType must not be null".formatted(patternId));
        }
        title = requireText(title, "title");
        description = requireText(description, "description");
        codeExample = codeExample == null ? "" : codeExample;
        if (frequency <= 0) {
            throw new DomainRuleViolationException("ReviewPattern[%s] frequency must be positive".formatted(patternId));
        }
        if (lastSeenAt == null) {
            throw new DomainRuleViolationException("ReviewPattern[%s] lastSeenAt must not be null".formatted(patternId));
        }
    }

    public ReviewPattern seenAgain(Instant seenAt) {
        if (seenAt == null) {
            throw new DomainRuleViolationException("ReviewPattern[%s] seenAt must not be null".formatted(patternId));
        }
        return new ReviewPattern(patternId, projectId, patternType, title, description, codeExample, frequency + 1, seenAt);
    }

    public enum PatternType {
        BUG_PATTERN,
        SECURITY_PATTERN,
        PERF_PATTERN,
        CONVENTION
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
