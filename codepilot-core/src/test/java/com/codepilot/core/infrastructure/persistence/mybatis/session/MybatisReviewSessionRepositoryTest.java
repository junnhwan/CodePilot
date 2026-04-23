package com.codepilot.core.infrastructure.persistence.mybatis.session;

import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.plan.TaskGraph;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.SessionEvent;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisReviewSessionRepositoryTest {

    @Test
    void savesAndRestoresReviewSessionWithEvents() {
        InMemoryReviewSessionMapper reviewSessionMapper = new InMemoryReviewSessionMapper();
        InMemorySessionEventMapper sessionEventMapper = new InMemorySessionEventMapper();
        MybatisReviewSessionRepository repository = new MybatisReviewSessionRepository(
                reviewSessionMapper,
                sessionEventMapper,
                JsonMapper.builder().findAndAddModules().build()
        );

        ReviewSession reviewSession = buildReviewSession();

        repository.save(reviewSession);
        Optional<ReviewSession> restored = repository.findById("session-42");

        assertThat(restored).isPresent();
        assertThat(restored.orElseThrow().state().name()).isEqualTo("DONE");
        assertThat(restored.orElseThrow().events()).hasSize(reviewSession.events().size());
        assertThat(repository.findEvents("session-42"))
                .extracting(SessionEvent::type)
                .contains(SessionEvent.Type.REVIEW_COMPLETED);
    }

    private ReviewSession buildReviewSession() {
        DiffSummary diffSummary = DiffSummary.of(List.of(new DiffSummary.ChangedFile(
                "src/main/java/App.java",
                DiffSummary.ChangeType.MODIFIED,
                8,
                2,
                List.of("App#run")
        )));
        ReviewTask reviewTask = ReviewTask.pending(
                "task-security",
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                List.of("src/main/java/App.java"),
                List.of("verify auth path"),
                List.of()
        );
        ReviewPlan reviewPlan = new ReviewPlan(
                "plan-42",
                "session-42",
                diffSummary,
                TaskGraph.of(List.of(reviewTask)),
                ReviewPlan.ReviewStrategy.SECURITY_FIRST
        );
        ReviewResult reviewResult = new ReviewResult(
                "session-42",
                List.of(Finding.reported(
                        "finding-42",
                        "task-security",
                        "security",
                        Severity.HIGH,
                        0.93d,
                        new Finding.CodeLocation("src/main/java/App.java", 18, 19),
                        "Authorization check is missing",
                        "State update occurs before ownership is verified.",
                        "Check ownership before mutating state.",
                        List.of("mutation precedes authorization guard")
                )),
                false,
                Instant.parse("2026-04-23T00:05:00Z")
        );

        return ReviewSession.initialize(
                        "session-42",
                        "project-alpha",
                        42,
                        "https://example.com/pr/42",
                        Instant.parse("2026-04-23T00:00:00Z")
                )
                .startPlanning(diffSummary, Instant.parse("2026-04-23T00:01:00Z"))
                .attachPlan(reviewPlan, Instant.parse("2026-04-23T00:02:00Z"))
                .startReviewing(Instant.parse("2026-04-23T00:03:00Z"))
                .startMerging(Instant.parse("2026-04-23T00:04:00Z"))
                .startReporting(reviewResult, Instant.parse("2026-04-23T00:05:00Z"))
                .markDone(reviewResult, Instant.parse("2026-04-23T00:06:00Z"));
    }

    private static final class InMemoryReviewSessionMapper implements ReviewSessionMapper {

        private ReviewSessionRow row;

        @Override
        public ReviewSessionRow selectById(String sessionId) {
            if (row == null || !row.sessionId().equals(sessionId)) {
                return null;
            }
            return row;
        }

        @Override
        public void upsert(ReviewSessionRow reviewSessionRow) {
            this.row = reviewSessionRow;
        }
    }

    private static final class InMemorySessionEventMapper implements SessionEventMapper {

        private final List<SessionEventRow> rows = new ArrayList<>();

        @Override
        public List<SessionEventRow> selectBySessionId(String sessionId) {
            return rows.stream()
                    .filter(row -> row.sessionId().equals(sessionId))
                    .sorted(Comparator.comparing(SessionEventRow::occurredAt))
                    .toList();
        }

        @Override
        public void deleteBySessionId(String sessionId) {
            rows.removeIf(row -> row.sessionId().equals(sessionId));
        }

        @Override
        public void insert(SessionEventRow sessionEventRow) {
            rows.add(sessionEventRow);
        }
    }
}
