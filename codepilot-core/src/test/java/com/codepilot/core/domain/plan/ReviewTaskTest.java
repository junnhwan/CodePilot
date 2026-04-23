package com.codepilot.core.domain.plan;

import com.codepilot.core.domain.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewTaskTest {

    @Test
    void taskMovesThroughReadyInProgressAndCompleted() {
        ReviewTask task = ReviewTask.pending(
                "task-security",
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                List.of("src/main/java/App.java"),
                List.of("review authentication path"),
                List.of()
        );

        ReviewTask completed = task.markReady().start().complete();

        assertThat(completed.state()).isEqualTo(ReviewTask.State.COMPLETED);
    }

    @Test
    void taskCannotStartBeforeItIsReady() {
        ReviewTask task = ReviewTask.pending(
                "task-style",
                ReviewTask.TaskType.STYLE,
                ReviewTask.Priority.LOW,
                List.of("src/main/java/App.java"),
                List.of(),
                List.of()
        );

        assertThatThrownBy(task::start)
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("task-style")
                .hasMessageContaining("PENDING");
    }
}
