package com.codepilot.core.application.plan;

import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningAgentTest {

    @Test
    void buildsSecurityFirstPlanWithFocusHintsPrioritiesAndDependencies() {
        PlanningAgent planningAgent = new PlanningAgent(new DiffAnalyzer());

        ReviewPlan reviewPlan = planningAgent.plan("session-42", """
                diff --git a/src/main/java/com/example/auth/AuthController.java b/src/main/java/com/example/auth/AuthController.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/auth/AuthController.java
                +++ b/src/main/java/com/example/auth/AuthController.java
                @@ -10,3 +10,9 @@
                +    public LoginResponse login(LoginRequest request) {
                +        if (request.token() == null) {
                +            throw new IllegalArgumentException("missing token");
                +        }
                +        return authService.login(request.username(), request.password(), request.token());
                +    }
                 }
                diff --git a/src/main/java/com/example/repository/UserRepository.java b/src/main/java/com/example/repository/UserRepository.java
                index 3333333..4444444 100644
                --- a/src/main/java/com/example/repository/UserRepository.java
                +++ b/src/main/java/com/example/repository/UserRepository.java
                @@ -20,3 +20,7 @@
                +    UserEntity findByName(String name) {
                +        return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", rowMapper);
                +    }
                 }
                diff --git a/src/main/java/com/example/service/UserService.java b/src/main/java/com/example/service/UserService.java
                index 5555555..6666666 100644
                --- a/src/main/java/com/example/service/UserService.java
                +++ b/src/main/java/com/example/service/UserService.java
                @@ -40,3 +40,7 @@
                +    void syncUsers(List<String> ids) {
                +        for (String id : ids) {
                +            userRepository.findById(id);
                +        }
                +    }
                 }
                """);

        Map<ReviewTask.TaskType, ReviewTask> tasksByType = reviewPlan.taskGraph().allTasks().stream()
                .collect(java.util.stream.Collectors.toMap(ReviewTask::type, Function.identity()));

        assertThat(reviewPlan.strategy()).isEqualTo(ReviewPlan.ReviewStrategy.SECURITY_FIRST);
        assertThat(tasksByType).containsKeys(
                ReviewTask.TaskType.SECURITY,
                ReviewTask.TaskType.PERF,
                ReviewTask.TaskType.STYLE,
                ReviewTask.TaskType.MAINTAIN
        );

        ReviewTask securityTask = tasksByType.get(ReviewTask.TaskType.SECURITY);
        ReviewTask perfTask = tasksByType.get(ReviewTask.TaskType.PERF);
        ReviewTask styleTask = tasksByType.get(ReviewTask.TaskType.STYLE);
        ReviewTask maintainTask = tasksByType.get(ReviewTask.TaskType.MAINTAIN);

        assertThat(securityTask.priority()).isEqualTo(ReviewTask.Priority.HIGH);
        assertThat(securityTask.targetFiles()).contains("src/main/java/com/example/auth/AuthController.java");
        assertThat(securityTask.focusHints()).anySatisfy(hint -> assertThat(hint).containsIgnoringCase("authorization"));

        assertThat(perfTask.priority()).isEqualTo(ReviewTask.Priority.HIGH);
        assertThat(perfTask.targetFiles()).contains("src/main/java/com/example/repository/UserRepository.java");
        assertThat(perfTask.focusHints()).anySatisfy(hint -> assertThat(hint).containsIgnoringCase("query"));

        assertThat(styleTask.dependencies()).containsExactly("task-security");
        assertThat(styleTask.focusHints()).anySatisfy(hint -> assertThat(hint).containsIgnoringCase("naming"));

        assertThat(maintainTask.priority()).isEqualTo(ReviewTask.Priority.HIGH);
        assertThat(maintainTask.dependencies()).containsExactly("task-security", "task-perf");
        assertThat(maintainTask.focusHints()).anySatisfy(hint -> assertThat(hint).containsIgnoringCase("complexity"));
    }
}
