package com.codepilot.core.domain.review;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ReviewResult(
        String sessionId,
        List<Finding> findings,
        boolean partial,
        Instant generatedAt
) {

    public ReviewResult {
        sessionId = requireText(sessionId, "sessionId");
        findings = findings == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(findings));
        if (generatedAt == null) {
            throw new DomainRuleViolationException("ReviewResult[%s] generatedAt must not be null".formatted(sessionId));
        }
    }

    public List<Finding> confirmedFindings() {
        return findings.stream()
                .filter(Finding::isIssueConfirmed)
                .toList();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
