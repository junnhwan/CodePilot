package com.codepilot.core.application.session;

import com.codepilot.core.application.plan.PlanningAgent;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.SessionEvent;
import com.codepilot.core.infrastructure.persistence.inmemory.InMemoryReviewSessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStoreTest {

    @Test
    void restoresReviewingSessionFromCheckpointAndReplaysCompletedTaskResults() {
        InMemoryReviewSessionRepository repository = new InMemoryReviewSessionRepository();
        SessionStore sessionStore = new SessionStore(repository);

        ReviewPlan reviewPlan = new PlanningAgent(new DiffAnalyzer()).plan("session-restore", rawDiff());
        ReviewTask securityTask = reviewPlan.taskGraph().allTasks().stream()
                .filter(task -> task.type() == ReviewTask.TaskType.SECURITY)
                .findFirst()
                .orElseThrow();

        ReviewSession reviewingSession = ReviewSession.initialize(
                        "session-restore",
                        "acme/repo",
                        42,
                        "https://github.com/acme/repo/pull/42",
                        Instant.parse("2026-04-24T01:00:00Z")
                )
                .startPlanning(reviewPlan.diffSummary(), Instant.parse("2026-04-24T01:01:00Z"))
                .attachPlan(reviewPlan, Instant.parse("2026-04-24T01:02:00Z"))
                .startReviewing(Instant.parse("2026-04-24T01:03:00Z"));
        repository.save(reviewingSession);

        repository.append(SessionEvent.of(
                "session-restore",
                SessionEvent.Type.TASK_STARTED,
                Instant.parse("2026-04-24T01:04:00Z"),
                Map.of(
                        "taskId", securityTask.taskId(),
                        "type", securityTask.type().name(),
                        "files", securityTask.targetFiles()
                )
        ));
        repository.append(SessionEvent.of(
                "session-restore",
                SessionEvent.Type.FINDING_REPORTED,
                Instant.parse("2026-04-24T01:04:10Z"),
                Map.ofEntries(
                        Map.entry("findingId", "finding-security-1"),
                        Map.entry("taskId", securityTask.taskId()),
                        Map.entry("category", "security"),
                        Map.entry("severity", Severity.HIGH.name()),
                        Map.entry("confidence", 0.98d),
                        Map.entry("file", "src/main/java/com/example/UserRepository.java"),
                        Map.entry("startLine", 5),
                        Map.entry("endLine", 5),
                        Map.entry("title", "Potential SQL injection risk"),
                        Map.entry("description", "User input is concatenated directly into SQL."),
                        Map.entry("suggestion", "Use a parameterized query."),
                        Map.entry("evidence", List.of("The query interpolates request data into SQL."))
                )
        ));
        repository.append(SessionEvent.of(
                "session-restore",
                SessionEvent.Type.TASK_COMPLETED,
                Instant.parse("2026-04-24T01:04:20Z"),
                Map.of(
                        "taskId", securityTask.taskId(),
                        "type", securityTask.type().name(),
                        "findingCount", 1,
                        "partial", false
                )
        ));

        SessionStore.RestoredSession restored = sessionStore.restore("session-restore").orElseThrow();

        assertThat(restored.session().state()).isEqualTo(AgentState.REVIEWING);
        ReviewTask restoredSecurityTask = restored.session().reviewPlan().taskGraph().allTasks().stream()
                .filter(task -> task.taskId().equals(securityTask.taskId()))
                .findFirst()
                .orElseThrow();
        assertThat(restoredSecurityTask.state()).isEqualTo(ReviewTask.State.COMPLETED);
        assertThat(restored.completedTaskResults()).hasSize(1);
        ReviewResult carriedResult = restored.completedTaskResults().getFirst();
        assertThat(carriedResult.partial()).isFalse();
        assertThat(carriedResult.findings())
                .extracting(Finding::title)
                .containsExactly("Potential SQL injection risk");
    }

    @Test
    void replaysMinimalSessionStateFromEventsWhenCheckpointIsMissing() {
        InMemoryReviewSessionRepository repository = new InMemoryReviewSessionRepository();
        SessionStore sessionStore = new SessionStore(repository);

        ReviewSession created = ReviewSession.initialize(
                "session-event-only",
                "acme/repo",
                7,
                "https://github.com/acme/repo/pull/7",
                Instant.parse("2026-04-24T02:00:00Z")
        );
        repository.append(created.events().getFirst());
        repository.append(SessionEvent.of(
                "session-event-only",
                SessionEvent.Type.SESSION_STATE_CHANGED,
                Instant.parse("2026-04-24T02:01:00Z"),
                Map.of("from", AgentState.IDLE.name(), "to", AgentState.PLANNING.name())
        ));

        SessionStore.RestoredSession restored = sessionStore.restore("session-event-only").orElseThrow();

        assertThat(restored.session().projectId()).isEqualTo("acme/repo");
        assertThat(restored.session().prNumber()).isEqualTo(7);
        assertThat(restored.session().prUrl()).isEqualTo("https://github.com/acme/repo/pull/7");
        assertThat(restored.session().state()).isEqualTo(AgentState.PLANNING);
        assertThat(restored.session().reviewPlan()).isNull();
        assertThat(restored.completedTaskResults()).isEmpty();
    }

    private String rawDiff() {
        return """
                diff --git a/src/main/java/com/example/UserRepository.java b/src/main/java/com/example/UserRepository.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/UserRepository.java
                +++ b/src/main/java/com/example/UserRepository.java
                @@ -1,1 +1,7 @@
                +package com.example;
                +
                +class UserRepository {
                +  String findByName(String name) {
                +    return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", String.class);
                +  }
                +}
                """;
    }
}
