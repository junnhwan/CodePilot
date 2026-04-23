package com.codepilot.core.domain.session;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.plan.TaskGraph;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewSessionTest {

    @Test
    void sessionMovesThroughPipelineAndRecordsEvents() {
        ReviewSession session = ReviewSession.initialize(
                "session-1",
                "project-alpha",
                42,
                "https://example.com/pr/42",
                Instant.parse("2026-04-23T00:00:00Z")
        );
        DiffSummary diff = DiffSummary.of(List.of(new DiffSummary.ChangedFile(
                "src/main/java/App.java",
                DiffSummary.ChangeType.MODIFIED,
                12,
                3,
                List.of("App#run")
        )));
        ReviewTask task = ReviewTask.pending(
                "task-security",
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                List.of("src/main/java/App.java"),
                List.of("review authentication branch"),
                List.of()
        );
        ReviewPlan plan = new ReviewPlan(
                "plan-1",
                "session-1",
                diff,
                TaskGraph.of(List.of(task)),
                ReviewPlan.ReviewStrategy.SECURITY_FIRST
        );
        ReviewResult result = new ReviewResult(
                "session-1",
                List.of(Finding.reported(
                        "finding-1",
                        "task-security",
                        "security",
                        Severity.HIGH,
                        0.91,
                        new Finding.CodeLocation("src/main/java/App.java", 18, 20),
                        "Authorization check is missing",
                        "The endpoint updates state before verifying ownership.",
                        "Verify ownership before mutating state.",
                        List.of("state mutation precedes authorization guard")
                )),
                false,
                Instant.parse("2026-04-23T00:05:00Z")
        );

        ReviewSession completed = session
                .startPlanning(diff, Instant.parse("2026-04-23T00:01:00Z"))
                .attachPlan(plan, Instant.parse("2026-04-23T00:02:00Z"))
                .startReviewing(Instant.parse("2026-04-23T00:03:00Z"))
                .startMerging(Instant.parse("2026-04-23T00:04:00Z"))
                .startReporting(result, Instant.parse("2026-04-23T00:05:00Z"))
                .markDone(result, Instant.parse("2026-04-23T00:06:00Z"));

        assertThat(completed.state()).isEqualTo(AgentState.DONE);
        assertThat(completed.events()).hasSize(7);
        assertThat(completed.events())
                .extracting(SessionEvent::type)
                .contains(SessionEvent.Type.PLAN_ATTACHED, SessionEvent.Type.REVIEW_COMPLETED);
    }

    @Test
    void sessionCannotSkipPlanningStage() {
        ReviewSession session = ReviewSession.initialize(
                "session-2",
                "project-beta",
                7,
                "https://example.com/pr/7",
                Instant.parse("2026-04-23T00:00:00Z")
        );

        assertThatThrownBy(() -> session.startReviewing(Instant.parse("2026-04-23T00:01:00Z")))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("session-2")
                .hasMessageContaining("IDLE");
    }
}
