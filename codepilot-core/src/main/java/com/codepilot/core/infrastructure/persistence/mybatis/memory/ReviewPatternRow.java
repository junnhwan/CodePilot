package com.codepilot.core.infrastructure.persistence.mybatis.memory;

import java.time.Instant;

public record ReviewPatternRow(
        String patternId,
        String projectId,
        String patternType,
        String title,
        String description,
        String codeExample,
        int frequency,
        Instant lastSeenAt
) {
}
