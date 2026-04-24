package com.codepilot.core.application.memory;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DreamServiceTest {

    @Test
    void extractsNewReviewPatternAndSavesUpdatedProjectMemory() {
        ProjectMemory existing = ProjectMemory.empty("demo-project");
        ReviewResult reviewResult = highSignalReviewResult(0.95d);

        InMemoryProjectMemoryRepository repository = new InMemoryProjectMemoryRepository(existing);
        DreamService dreamService = new DreamService(repository);

        ProjectMemory updated = dreamService.dream("demo-project", reviewResult);

        assertThat(updated.reviewPatterns())
                .extracting(ReviewPattern::title)
                .contains("Missing token guard before repository access");
        assertThat(repository.saveCount()).isEqualTo(1);
    }

    @Test
    void skipsDreamWhenReviewResultHasNoFindings() {
        InMemoryProjectMemoryRepository repository = new InMemoryProjectMemoryRepository(ProjectMemory.empty("demo-project"));
        DreamService dreamService = new DreamService(repository);

        ProjectMemory updated = dreamService.dream(
                "demo-project",
                new ReviewResult("session-empty", java.util.List.of(), false, Instant.parse("2026-04-24T00:00:00Z"))
        );

        assertThat(updated.reviewPatterns()).isEmpty();
        assertThat(repository.saveCount()).isZero();
    }

    @Test
    void skipsLowConfidenceFindings() {
        InMemoryProjectMemoryRepository repository = new InMemoryProjectMemoryRepository(ProjectMemory.empty("demo-project"));
        DreamService dreamService = new DreamService(repository);

        ProjectMemory updated = dreamService.dream("demo-project", highSignalReviewResult(0.60d));

        assertThat(updated.reviewPatterns()).isEmpty();
        assertThat(repository.saveCount()).isZero();
    }

    @Test
    void doesNotInsertDuplicatePatternWhenDreamRunsTwiceForSameReviewResult() {
        InMemoryProjectMemoryRepository repository = new InMemoryProjectMemoryRepository(ProjectMemory.empty("demo-project"));
        DreamService dreamService = new DreamService(repository);
        ReviewResult reviewResult = highSignalReviewResult(0.95d);

        dreamService.dream("demo-project", reviewResult);
        ProjectMemory updated = dreamService.dream("demo-project", reviewResult);

        assertThat(updated.reviewPatterns()).hasSize(1);
        assertThat(updated.reviewPatterns().getFirst().frequency()).isEqualTo(1);
        assertThat(repository.saveCount()).isEqualTo(1);
    }

    @Test
    void includesProjectAndSessionContextWhenPersistenceFails() {
        InMemoryProjectMemoryRepository repository = new InMemoryProjectMemoryRepository(ProjectMemory.empty("demo-project"));
        repository.failOnSave(new IllegalStateException("storage offline"));
        DreamService dreamService = new DreamService(repository);

        assertThatThrownBy(() -> dreamService.dream("demo-project", highSignalReviewResult(0.95d)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo-project")
                .hasMessageContaining("session-1")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private static ReviewResult highSignalReviewResult(double confidence) {
        return new ReviewResult(
                "session-1",
                java.util.List.of(Finding.reported(
                        "finding-1",
                        "task-security",
                        "security",
                        Severity.HIGH,
                        confidence,
                        new Finding.CodeLocation("src/main/java/com/example/AuthService.java", 42, 42),
                        "Missing token guard before repository access",
                        "tokenRepository is reached before validation.",
                        "Call tokenGuard.requireProjectAccess(token) first.",
                        java.util.List.of("The repository access appears before the guard call.")
                )),
                false,
                Instant.parse("2026-04-24T00:00:00Z")
        );
    }

    private static final class InMemoryProjectMemoryRepository implements ProjectMemoryRepository {

        private ProjectMemory projectMemory;

        private RuntimeException saveFailure;

        private int saveCount;

        private InMemoryProjectMemoryRepository(ProjectMemory projectMemory) {
            this.projectMemory = projectMemory;
        }

        @Override
        public Optional<ProjectMemory> findByProjectId(String projectId) {
            if (projectMemory == null || !projectMemory.projectId().equals(projectId)) {
                return Optional.empty();
            }
            return Optional.of(projectMemory);
        }

        @Override
        public void save(ProjectMemory projectMemory) {
            if (saveFailure != null) {
                throw saveFailure;
            }
            this.projectMemory = projectMemory;
            this.saveCount++;
        }

        private void failOnSave(RuntimeException saveFailure) {
            this.saveFailure = saveFailure;
        }

        private int saveCount() {
            return saveCount;
        }
    }
}
