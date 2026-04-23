package com.codepilot.core.infrastructure.persistence.mybatis.memory;

public record TeamConventionRow(
        String conventionId,
        String projectId,
        String category,
        String rule,
        String exampleGood,
        String exampleBad,
        double confidence,
        String source
) {
}
