package com.codepilot.core.domain.review;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record Finding(
        String findingId,
        String taskId,
        String category,
        Severity severity,
        double confidence,
        CodeLocation location,
        String title,
        String description,
        String suggestion,
        List<String> evidence,
        Status status,
        String dispositionReason
) {

    public Finding {
        findingId = requireText(findingId, "findingId");
        taskId = requireText(taskId, "taskId");
        category = requireText(category, "category");
        if (severity == null) {
            throw new DomainRuleViolationException("Finding[%s] severity must not be null".formatted(findingId));
        }
        if (confidence < 0.0d || confidence > 1.0d) {
            throw new DomainRuleViolationException("Finding[%s] confidence must be within [0, 1]".formatted(findingId));
        }
        if (location == null) {
            throw new DomainRuleViolationException("Finding[%s] location must not be null".formatted(findingId));
        }
        title = requireText(title, "title");
        description = requireText(description, "description");
        suggestion = suggestion == null ? "" : suggestion;
        evidence = evidence == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(evidence));
        if (status == null) {
            throw new DomainRuleViolationException("Finding[%s] status must not be null".formatted(findingId));
        }
        dispositionReason = dispositionReason == null ? "" : dispositionReason;
    }

    public static Finding reported(
            String findingId,
            String taskId,
            String category,
            Severity severity,
            double confidence,
            CodeLocation location,
            String title,
            String description,
            String suggestion,
            List<String> evidence
    ) {
        return new Finding(
                findingId,
                taskId,
                category,
                severity,
                confidence,
                location,
                title,
                description,
                suggestion,
                evidence,
                Status.REPORTED,
                ""
        );
    }

    public Finding confirm() {
        requireStatus(Status.REPORTED, "confirm");
        return withStatus(Status.CONFIRMED, severity, "");
    }

    public Finding dismiss(String reason) {
        requireStatus(Status.REPORTED, "dismiss");
        return withStatus(Status.DISMISSED, severity, requireText(reason, "reason"));
    }

    public Finding downgradeTo(Severity downgradedSeverity, String reason) {
        requireStatus(Status.REPORTED, "downgrade");
        if (downgradedSeverity == null || !severity.moreSevereThan(downgradedSeverity)) {
            throw new DomainRuleViolationException("Finding[%s] can only downgrade to a less severe level".formatted(findingId));
        }
        return withStatus(Status.DOWNGRADED, downgradedSeverity, requireText(reason, "reason"));
    }

    public boolean isIssueConfirmed() {
        return status == Status.CONFIRMED;
    }

    private Finding withStatus(Status nextStatus, Severity nextSeverity, String nextReason) {
        return new Finding(
                findingId,
                taskId,
                category,
                nextSeverity,
                confidence,
                location,
                title,
                description,
                suggestion,
                evidence,
                nextStatus,
                nextReason
        );
    }

    private void requireStatus(Status expected, String action) {
        if (status != expected) {
            throw new DomainRuleViolationException("Finding[%s] cannot %s from %s".formatted(findingId, action, status));
        }
    }

    public record CodeLocation(
            String filePath,
            int startLine,
            int endLine
    ) {

        public CodeLocation {
            filePath = requireText(filePath, "filePath");
            if (startLine <= 0 || endLine < startLine) {
                throw new DomainRuleViolationException("CodeLocation[%s] has invalid line range %d-%d"
                        .formatted(filePath, startLine, endLine));
            }
        }
    }

    public enum Status {
        REPORTED,
        CONFIRMED,
        DISMISSED,
        DOWNGRADED
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
