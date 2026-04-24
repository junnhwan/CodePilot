package com.codepilot.core.application.memory;

import com.codepilot.core.domain.memory.GlobalKnowledgeEntry;
import com.codepilot.core.domain.plan.ReviewTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalKnowledgeServiceTest {

    @Test
    void returnsSecurityGuidanceForTokenAndSqlSignals() {
        GlobalKnowledgeService service = new GlobalKnowledgeService(List.of(
                new GlobalKnowledgeEntry(
                        "security-sql-001",
                        ReviewTask.TaskType.SECURITY,
                        "Parameterized queries required",
                        "Avoid string-built SQL and require prepared statements for repository queries.",
                        List.of("sql", "query", "repository", "jdbc")
                ),
                new GlobalKnowledgeEntry(
                        "security-token-001",
                        ReviewTask.TaskType.SECURITY,
                        "Token guard before repository access",
                        "Validate the token and project access before repository calls.",
                        List.of("token", "guard", "repository", "access")
                ),
                new GlobalKnowledgeEntry(
                        "perf-batch-001",
                        ReviewTask.TaskType.PERF,
                        "Batch repository lookups in loops",
                        "Avoid repeated repository calls inside loops; prefer batching.",
                        List.of("loop", "batch", "repository")
                )
        ));

        List<GlobalKnowledgeEntry> entries = service.recall(
                ReviewTask.TaskType.SECURITY,
                Set.of("token", "sql", "repository"),
                200
        );

        assertThat(entries).extracting(GlobalKnowledgeEntry::title)
                .contains("Parameterized queries required", "Token guard before repository access")
                .doesNotContain("Batch repository lookups in loops");
    }
}
