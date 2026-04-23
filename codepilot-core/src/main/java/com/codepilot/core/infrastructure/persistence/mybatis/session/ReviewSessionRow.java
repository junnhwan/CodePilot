package com.codepilot.core.infrastructure.persistence.mybatis.session;

import java.time.Instant;

public record ReviewSessionRow(
        String sessionId,
        String projectId,
        Integer prNumber,
        String prUrl,
        String state,
        String diffSummaryJson,
        String reviewPlanJson,
        String reviewResultJson,
        Instant createdAt,
        Instant completedAt
) {
}
