package com.codepilot.core.domain.plan;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.context.DiffSummary;

public record ReviewPlan(
        String planId,
        String sessionId,
        DiffSummary diffSummary,
        TaskGraph taskGraph,
        ReviewStrategy strategy
) {

    public ReviewPlan {
        planId = requireText(planId, "planId");
        sessionId = requireText(sessionId, "sessionId");
        if (diffSummary == null) {
            throw new DomainRuleViolationException("ReviewPlan[%s] diffSummary must not be null".formatted(planId));
        }
        if (taskGraph == null) {
            throw new DomainRuleViolationException("ReviewPlan[%s] taskGraph must not be null".formatted(planId));
        }
        if (strategy == null) {
            throw new DomainRuleViolationException("ReviewPlan[%s] strategy must not be null".formatted(planId));
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public enum ReviewStrategy {
        SECURITY_FIRST,
        PERFORMANCE_FIRST,
        COMPREHENSIVE,
        QUICK_SCAN
    }
}
