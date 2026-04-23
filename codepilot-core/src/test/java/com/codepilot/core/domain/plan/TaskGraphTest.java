package com.codepilot.core.domain.plan;

import com.codepilot.core.domain.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskGraphTest {

    @Test
    void rejectsUnknownDependency() {
        ReviewTask task = ReviewTask.pending(
                "task-maintain",
                ReviewTask.TaskType.MAINTAIN,
                ReviewTask.Priority.MEDIUM,
                List.of("src/main/java/App.java"),
                List.of("inspect coupling"),
                List.of("task-missing")
        );

        assertThatThrownBy(() -> TaskGraph.of(List.of(task)))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("task-missing");
    }

    @Test
    void rejectsCyclesInsideTaskGraph() {
        ReviewTask first = ReviewTask.pending(
                "task-a",
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                List.of("A.java"),
                List.of(),
                List.of("task-b")
        );
        ReviewTask second = ReviewTask.pending(
                "task-b",
                ReviewTask.TaskType.PERF,
                ReviewTask.Priority.HIGH,
                List.of("B.java"),
                List.of(),
                List.of("task-a")
        );

        assertThatThrownBy(() -> TaskGraph.of(List.of(first, second)))
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void availableTasksOnlyExposeDependenciesThatAreAlreadyFinished() {
        ReviewTask completedRoot = ReviewTask.pending(
                        "task-root",
                        ReviewTask.TaskType.SECURITY,
                        ReviewTask.Priority.HIGH,
                        List.of("Root.java"),
                        List.of(),
                        List.of()
                )
                .markReady()
                .start()
                .complete();
        ReviewTask readyAfterRoot = ReviewTask.pending(
                "task-dependent",
                ReviewTask.TaskType.STYLE,
                ReviewTask.Priority.MEDIUM,
                List.of("Dependent.java"),
                List.of(),
                List.of("task-root")
        );
        ReviewTask stillBlocked = ReviewTask.pending(
                "task-blocked",
                ReviewTask.TaskType.MAINTAIN,
                ReviewTask.Priority.LOW,
                List.of("Blocked.java"),
                List.of(),
                List.of("task-dependent")
        );

        TaskGraph graph = TaskGraph.of(List.of(completedRoot, readyAfterRoot, stillBlocked));

        assertThat(graph.availableTasks())
                .extracting(ReviewTask::taskId)
                .containsExactly("task-dependent");
    }
}
