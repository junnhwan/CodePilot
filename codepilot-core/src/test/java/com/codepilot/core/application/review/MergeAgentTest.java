package com.codepilot.core.application.review;

import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MergeAgentTest {

    @Test
    void deduplicatesSortsAndFiltersLowSignalFindingsWithoutConfirmingIssues() {
        MergeAgent mergeAgent = new MergeAgent();

        Finding securityFinding = Finding.reported(
                "finding-security",
                "task-security",
                "security",
                Severity.HIGH,
                0.95,
                new Finding.CodeLocation("src/main/java/com/example/UserRepository.java", 14, 14),
                "SQL injection risk",
                "User input is concatenated into SQL.",
                "Use a parameterized query.",
                List.of("The query string contains '+ name +'.")
        );
        Finding duplicateMaintainFinding = Finding.reported(
                "finding-maintain",
                "task-maintain",
                "maintain",
                Severity.MEDIUM,
                0.72,
                new Finding.CodeLocation("src/main/java/com/example/UserRepository.java", 14, 14),
                "SQL injection risk",
                "A string-built query is hard to reason about and duplicates unsafe behavior.",
                "Move query construction to a prepared statement.",
                List.of("The same changed line assembles SQL from request data.")
        );
        Finding perfFinding = Finding.reported(
                "finding-perf",
                "task-perf",
                "perf",
                Severity.MEDIUM,
                0.89,
                new Finding.CodeLocation("src/main/java/com/example/UserService.java", 27, 29),
                "Repository call inside loop",
                "The loop issues one repository lookup per id.",
                "Batch load the records before iterating.",
                List.of("userRepository.findById(id) is called inside a for-loop.")
        );
        Finding lowSignalStyleFinding = Finding.reported(
                "finding-style",
                "task-style",
                "style",
                Severity.LOW,
                0.31,
                new Finding.CodeLocation("src/main/java/com/example/UserService.java", 11, 11),
                "Variable naming could be clearer",
                "The local variable name is short and generic.",
                "Consider a more explicit name.",
                List.of()
        );

        ReviewResult merged = mergeAgent.merge(
                "session-merge",
                List.of(
                        new ReviewResult("session-merge", List.of(securityFinding, lowSignalStyleFinding), false, Instant.parse("2026-04-24T09:00:00Z")),
                        new ReviewResult("session-merge", List.of(duplicateMaintainFinding, perfFinding), true, Instant.parse("2026-04-24T09:01:00Z"))
                )
        );

        assertThat(merged.partial()).isTrue();
        assertThat(merged.findings()).hasSize(2);
        assertThat(merged.findings())
                .extracting(Finding::title)
                .containsExactly("SQL injection risk", "Repository call inside loop");
        assertThat(merged.findings().getFirst().status()).isEqualTo(Finding.Status.REPORTED);
        assertThat(merged.findings().getFirst().evidence())
                .contains("The query string contains '+ name +'.", "The same changed line assembles SQL from request data.");
    }
}
