package com.codepilot.core.domain.memory;

import com.codepilot.core.domain.DomainRuleViolationException;

public record TeamConvention(
        String conventionId,
        String projectId,
        Category category,
        String rule,
        String exampleGood,
        String exampleBad,
        double confidence,
        Source source
) {

    public TeamConvention {
        conventionId = requireText(conventionId, "conventionId");
        projectId = requireText(projectId, "projectId");
        if (category == null) {
            throw new DomainRuleViolationException("TeamConvention[%s] category must not be null".formatted(conventionId));
        }
        rule = requireText(rule, "rule");
        exampleGood = exampleGood == null ? "" : exampleGood;
        exampleBad = exampleBad == null ? "" : exampleBad;
        if (confidence < 0.0d || confidence > 1.0d) {
            throw new DomainRuleViolationException("TeamConvention[%s] confidence must be within [0, 1]"
                    .formatted(conventionId));
        }
        if (source == null) {
            throw new DomainRuleViolationException("TeamConvention[%s] source must not be null".formatted(conventionId));
        }
    }

    public enum Category {
        NAMING,
        FORMAT,
        ARCHITECTURE,
        DEPENDENCY,
        SECURITY
    }

    public enum Source {
        MANUAL,
        LEARNED,
        DEFAULT
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
