package com.codepilot.eval;

import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScorecardTest {

    @Test
    void summarizesPrecisionRecallAndScenarioOutcomes() {
        EvalScenario issueScenario = new EvalScenario(
                "scenario-issue",
                "Issue scenario",
                "Reports one real security finding.",
                "project-issue",
                null,
                List.of(new EvalScenario.RepositoryFile("src/main/java/com/example/Sample.java", List.of("class Sample {}"))),
                List.of("diff --git a/src/main/java/com/example/Sample.java b/src/main/java/com/example/Sample.java"),
                null,
                List.of(new EvalScenario.GroundTruthFinding(
                        new Finding.CodeLocation("src/main/java/com/example/Sample.java", 12, 12),
                        Severity.HIGH,
                        "security",
                        "SQL injection risk"
                )),
                new EvalScenario.StopPolicy(6, 5)
        );
        EvalScenario cleanScenario = new EvalScenario(
                "scenario-clean",
                "Clean scenario",
                "Contains no issue but receives a false positive.",
                "project-clean",
                null,
                List.of(new EvalScenario.RepositoryFile("src/main/java/com/example/Clean.java", List.of("class Clean {}"))),
                List.of("diff --git a/src/main/java/com/example/Clean.java b/src/main/java/com/example/Clean.java"),
                null,
                List.of(),
                new EvalScenario.StopPolicy(6, 5)
        );

        EvalRunner.ScenarioResult matched = new EvalRunner.ScenarioResult(
                issueScenario,
                null,
                new ReviewResult(
                        "session-1",
                        List.of(Finding.reported(
                                "finding-1",
                                "task-security",
                                "security",
                                Severity.HIGH,
                                0.98,
                                new Finding.CodeLocation("src/main/java/com/example/Sample.java", 12, 12),
                                "SQL injection risk",
                                "User input reaches SQL directly.",
                                "Bind the user input as a parameter.",
                                List.of("The SQL line concatenates user input.")
                        )),
                        false,
                        Instant.parse("2026-04-24T00:00:00Z")
                ),
                120,
                240,
                null
        );
        EvalRunner.ScenarioResult falsePositive = new EvalRunner.ScenarioResult(
                cleanScenario,
                null,
                new ReviewResult(
                        "session-2",
                        List.of(Finding.reported(
                                "finding-2",
                                "task-style",
                                "style",
                                Severity.LOW,
                                0.75,
                                new Finding.CodeLocation("src/main/java/com/example/Clean.java", 8, 8),
                                "Variable naming could be clearer",
                                "A harmless rename was flagged.",
                                "Rename the variable.",
                                List.of("No concrete defect.")
                        )),
                        false,
                        Instant.parse("2026-04-24T00:00:05Z")
                ),
                80,
                160,
                null
        );

        Scorecard scorecard = Scorecard.from("run-test", List.of(matched, falsePositive));

        assertThat(scorecard.scenariosTotal()).isEqualTo(2);
        assertThat(scorecard.scenariosPassed()).isEqualTo(1);
        assertThat(scorecard.scenariosFailed()).isEqualTo(1);
        assertThat(scorecard.scenariosError()).isZero();
        assertThat(scorecard.metrics().precision()).isEqualTo(0.5d);
        assertThat(scorecard.metrics().recall()).isEqualTo(1.0d);
        assertThat(scorecard.metrics().falsePositiveRate()).isEqualTo(0.5d);
        assertThat(scorecard.metrics().endToEndSuccessRate()).isEqualTo(1.0d);
        assertThat(scorecard.metrics().avgContextTokensUsed()).isEqualTo(200.0d);
    }
}
