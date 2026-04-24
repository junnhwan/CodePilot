package com.codepilot.eval;

import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LintOnlyBaselineReviewerTest {

    @Test
    void flagsStringBuiltSqlButKeepsSafeRefactorClean() {
        LintOnlyBaselineReviewer reviewer = new LintOnlyBaselineReviewer();

        ReviewResult risky = reviewer.review("lint-session-1", sqlInjectionScenario());
        ReviewResult clean = reviewer.review("lint-session-2", safeRefactorScenario());

        assertThat(risky.findings())
                .extracting(finding -> finding.title())
                .contains("SQL injection risk");
        assertThat(clean.findings()).isEmpty();
    }

    @Test
    void flagsRepositoryLoopHardcodedTokenAndSwallowedException() {
        LintOnlyBaselineReviewer reviewer = new LintOnlyBaselineReviewer();

        ReviewResult hardcodedToken = reviewer.review("lint-session-3", hardcodedTokenScenario());
        ReviewResult repositoryLoop = reviewer.review("lint-session-4", repositoryLoopScenario());
        ReviewResult swallowedException = reviewer.review("lint-session-5", swallowedExceptionScenario());

        assertThat(hardcodedToken.findings())
                .extracting(finding -> finding.title())
                .contains("Hardcoded API token");
        assertThat(repositoryLoop.findings())
                .extracting(finding -> finding.title())
                .contains("Repository call inside loop");
        assertThat(swallowedException.findings())
                .extracting(finding -> finding.title())
                .contains("Exception swallowed");
    }

    private EvalScenario sqlInjectionScenario() {
        return new EvalScenario(
                "eval-lint-sql-001",
                "SQL injection lint scenario",
                "The diff builds SQL with string concatenation.",
                "lint-sql-project",
                null,
                List.of(new EvalScenario.RepositoryFile(
                        "src/main/java/com/example/user/UserRepository.java",
                        List.of(
                                "package com.example.user;",
                                "",
                                "class UserRepository {",
                                "    String findByName(String name) {",
                                "        return jdbcTemplate.queryForObject(",
                                "                \"select * from users where name = '\" + name + \"'\",",
                                "                String.class);",
                                "    }",
                                "}"
                        )
                )),
                List.of(
                        "diff --git a/src/main/java/com/example/user/UserRepository.java b/src/main/java/com/example/user/UserRepository.java",
                        "@@ -1,3 +1,9 @@",
                        "+    String findByName(String name) {",
                        "+        return jdbcTemplate.queryForObject(",
                        "+                \"select * from users where name = '\" + name + \"'\",",
                        "+                String.class);",
                        "+    }"
                ),
                null,
                List.of(new EvalScenario.GroundTruthFinding(
                        "src/main/java/com/example/user/UserRepository.java",
                        6,
                        6,
                        Severity.HIGH,
                        "security",
                        "SQL injection risk"
                )),
                new EvalScenario.StopPolicy(6, 5)
        );
    }

    private EvalScenario safeRefactorScenario() {
        return new EvalScenario(
                "eval-lint-safe-001",
                "Safe refactor lint scenario",
                "The diff is only a naming cleanup.",
                "lint-safe-project",
                null,
                List.of(new EvalScenario.RepositoryFile(
                        "src/main/java/com/example/profile/ProfileFormatter.java",
                        List.of(
                                "package com.example.profile;",
                                "",
                                "class ProfileFormatter {",
                                "    String format(String userName) {",
                                "        String normalizedUserName = userName == null ? \"\" : userName.trim();",
                                "        return normalizedUserName.toUpperCase();",
                                "    }",
                                "}"
                        )
                )),
                List.of(
                        "diff --git a/src/main/java/com/example/profile/ProfileFormatter.java b/src/main/java/com/example/profile/ProfileFormatter.java",
                        "@@ -2,5 +2,5 @@",
                        "-    String format(String name) {",
                        "-        String normalized = name == null ? \"\" : name.trim();",
                        "-        return normalized.toUpperCase();",
                        "+    String format(String userName) {",
                        "+        String normalizedUserName = userName == null ? \"\" : userName.trim();",
                        "+        return normalizedUserName.toUpperCase();"
                ),
                null,
                List.of(),
                new EvalScenario.StopPolicy(6, 5)
        );
    }

    private EvalScenario hardcodedTokenScenario() {
        return new EvalScenario(
                "eval-lint-token-001",
                "Hardcoded token lint scenario",
                "The diff adds a token literal.",
                "lint-token-project",
                null,
                List.of(new EvalScenario.RepositoryFile(
                        "src/main/java/com/example/github/GitHubWebhookClient.java",
                        List.of(
                                "package com.example.github;",
                                "",
                                "class GitHubWebhookClient {",
                                "    private static final String FALLBACK_GITHUB_TOKEN = \"ghp_demo_insecure_token\";",
                                "}"
                        )
                )),
                List.of(
                        "diff --git a/src/main/java/com/example/github/GitHubWebhookClient.java b/src/main/java/com/example/github/GitHubWebhookClient.java",
                        "@@ -1,3 +1,5 @@",
                        "+class GitHubWebhookClient {",
                        "+    private static final String FALLBACK_GITHUB_TOKEN = \"ghp_demo_insecure_token\";",
                        "+}"
                ),
                null,
                List.of(new EvalScenario.GroundTruthFinding(
                        "src/main/java/com/example/github/GitHubWebhookClient.java",
                        4,
                        4,
                        Severity.HIGH,
                        "security",
                        "Hardcoded API token"
                )),
                new EvalScenario.StopPolicy(6, 5)
        );
    }

    private EvalScenario repositoryLoopScenario() {
        return new EvalScenario(
                "eval-lint-loop-001",
                "Repository loop lint scenario",
                "The diff adds a repository access inside a loop.",
                "lint-loop-project",
                null,
                List.of(new EvalScenario.RepositoryFile(
                        "src/main/java/com/example/order/OrderService.java",
                        List.of(
                                "package com.example.order;",
                                "",
                                "class OrderService {",
                                "    void load(List<String> orderIds) {",
                                "        for (String orderId : orderIds) {",
                                "            orders.add(orderRepository.findById(orderId));",
                                "        }",
                                "    }",
                                "}"
                        )
                )),
                List.of(
                        "diff --git a/src/main/java/com/example/order/OrderService.java b/src/main/java/com/example/order/OrderService.java",
                        "@@ -1,3 +1,9 @@",
                        "+class OrderService {",
                        "+    void load(List<String> orderIds) {",
                        "+        for (String orderId : orderIds) {",
                        "+            orders.add(orderRepository.findById(orderId));",
                        "+        }",
                        "+    }",
                        "+}"
                ),
                null,
                List.of(new EvalScenario.GroundTruthFinding(
                        "src/main/java/com/example/order/OrderService.java",
                        6,
                        6,
                        Severity.MEDIUM,
                        "perf",
                        "Repository call inside loop"
                )),
                new EvalScenario.StopPolicy(6, 5)
        );
    }

    private EvalScenario swallowedExceptionScenario() {
        return new EvalScenario(
                "eval-lint-catch-001",
                "Swallowed exception lint scenario",
                "The diff introduces a catch block that only ignores errors.",
                "lint-catch-project",
                null,
                List.of(new EvalScenario.RepositoryFile(
                        "src/main/java/com/example/audit/WebhookAuditWriter.java",
                        List.of(
                                "package com.example.audit;",
                                "",
                                "import java.io.IOException;",
                                "",
                                "class WebhookAuditWriter {",
                                "    void record(String payload) {",
                                "        try {",
                                "            auditStore.write(payload);",
                                "        } catch (IOException error) {",
                                "            // ignored during retry handoff",
                                "        }",
                                "    }",
                                "}"
                        )
                )),
                List.of(
                        "diff --git a/src/main/java/com/example/audit/WebhookAuditWriter.java b/src/main/java/com/example/audit/WebhookAuditWriter.java",
                        "@@ -1,3 +1,13 @@",
                        "+import java.io.IOException;",
                        "+",
                        "+class WebhookAuditWriter {",
                        "+    void record(String payload) {",
                        "+        try {",
                        "+            auditStore.write(payload);",
                        "+        } catch (IOException error) {",
                        "+            // ignored during retry handoff",
                        "+        }",
                        "+    }",
                        "+}"
                ),
                null,
                List.of(new EvalScenario.GroundTruthFinding(
                        "src/main/java/com/example/audit/WebhookAuditWriter.java",
                        9,
                        9,
                        Severity.MEDIUM,
                        "maintain",
                        "Exception swallowed"
                )),
                new EvalScenario.StopPolicy(6, 5)
        );
    }
}
