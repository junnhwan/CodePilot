package com.codepilot.core.application.review;

import com.codepilot.core.application.plan.PlanningAgent;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.plan.TaskGraph;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class ReviewOrchestrator {

    private static final Executor DEFAULT_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("codepilot-review-", 0).factory()
    );

    private final PlanningAgent planningAgent;

    private final ContextCompiler contextCompiler;

    private final ReviewEngine reviewEngine;

    private final ReviewerPool reviewerPool;

    private final MergeAgent mergeAgent;

    private final Executor executor;

    public ReviewOrchestrator(
            PlanningAgent planningAgent,
            ContextCompiler contextCompiler,
            ReviewEngine reviewEngine,
            ReviewerPool reviewerPool,
            MergeAgent mergeAgent
    ) {
        this(planningAgent, contextCompiler, reviewEngine, reviewerPool, mergeAgent, DEFAULT_EXECUTOR);
    }

    public ReviewOrchestrator(
            PlanningAgent planningAgent,
            ContextCompiler contextCompiler,
            ReviewEngine reviewEngine,
            ReviewerPool reviewerPool,
            MergeAgent mergeAgent,
            Executor executor
    ) {
        this.planningAgent = planningAgent;
        this.contextCompiler = contextCompiler;
        this.reviewEngine = reviewEngine;
        this.reviewerPool = reviewerPool;
        this.mergeAgent = mergeAgent;
        this.executor = executor == null ? DEFAULT_EXECUTOR : executor;
    }

    public RunResult run(
            String sessionId,
            Path repoRoot,
            String rawDiff,
            ProjectMemory projectMemory,
            Map<String, String> structuredFacts
    ) {
        return run(sessionId, repoRoot, rawDiff, projectMemory, structuredFacts, new Listener() {
        });
    }

    public RunResult run(
            String sessionId,
            Path repoRoot,
            String rawDiff,
            ProjectMemory projectMemory,
            Map<String, String> structuredFacts,
            Listener listener
    ) {
        Listener effectiveListener = listener == null ? new Listener() {
        } : listener;
        ReviewPlan reviewPlan = planningAgent.plan(sessionId, rawDiff);
        effectiveListener.onPlanReady(reviewPlan);
        return executeInternal(reviewPlan, repoRoot, rawDiff, projectMemory, structuredFacts, List.of(), effectiveListener);
    }

    public RunResult execute(
            ReviewPlan reviewPlan,
            Path repoRoot,
            String rawDiff,
            ProjectMemory projectMemory,
            Map<String, String> structuredFacts
    ) {
        return execute(reviewPlan, repoRoot, rawDiff, projectMemory, structuredFacts, List.of(), new Listener() {
        });
    }

    public RunResult execute(
            ReviewPlan reviewPlan,
            Path repoRoot,
            String rawDiff,
            ProjectMemory projectMemory,
            Map<String, String> structuredFacts,
            Listener listener
    ) {
        return execute(reviewPlan, repoRoot, rawDiff, projectMemory, structuredFacts, List.of(), listener);
    }

    public RunResult execute(
            ReviewPlan reviewPlan,
            Path repoRoot,
            String rawDiff,
            ProjectMemory projectMemory,
            Map<String, String> structuredFacts,
            List<ReviewResult> seedResults,
            Listener listener
    ) {
        return executeInternal(
                reviewPlan,
                repoRoot,
                rawDiff,
                projectMemory,
                structuredFacts,
                seedResults == null ? List.of() : seedResults,
                listener == null ? new Listener() {
                } : listener
        );
    }

    private RunResult executeInternal(
            ReviewPlan reviewPlan,
            Path repoRoot,
            String rawDiff,
            ProjectMemory projectMemory,
            Map<String, String> structuredFacts,
            List<ReviewResult> seedResults,
            Listener effectiveListener
    ) {
        String sessionId = reviewPlan.sessionId();
        ContextPack contextPack = contextCompiler.compile(
                repoRoot.toAbsolutePath().normalize(),
                rawDiff,
                projectMemory == null ? ProjectMemory.empty(sessionId) : projectMemory,
                structuredFacts == null ? Map.of() : structuredFacts
        );

        TaskGraph taskGraph = reviewPlan.taskGraph();
        List<ReviewResult> taskResults = new ArrayList<>(seedResults);

        while (taskGraph.allTasks().stream().anyMatch(task -> !task.isTerminal())) {
            List<ReviewTask> availableTasks = taskGraph.availableTasks();
            if (availableTasks.isEmpty()) {
                throw new IllegalStateException("ReviewPlan[%s] has no schedulable tasks remaining".formatted(reviewPlan.planId()));
            }

            List<CompletableFuture<TaskExecution>> futures = availableTasks.stream()
                    .map(reviewTask -> CompletableFuture.supplyAsync(
                            () -> executeTask(sessionId, reviewTask, contextPack, effectiveListener),
                            executor
                    ))
                    .toList();

            for (CompletableFuture<TaskExecution> future : futures) {
                TaskExecution taskExecution;
                try {
                    taskExecution = future.join();
                } catch (CompletionException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    throw cause instanceof RuntimeException runtimeException
                            ? runtimeException
                            : new IllegalStateException("Failed to execute review task for session " + sessionId, cause);
                }
                taskGraph = taskGraph.replace(taskExecution.completedTask());
                taskResults.add(taskExecution.reviewResult());
            }
        }

        return new RunResult(reviewPlan, mergeAgent.merge(sessionId, taskResults));
    }

    private TaskExecution executeTask(
            String sessionId,
            ReviewTask readyTask,
            ContextPack contextPack,
            Listener listener
    ) {
        ReviewTask startedTask = readyTask.start();
        listener.onTaskStarted(startedTask, contextPack);

        ReviewResult reviewResult;
        try {
            reviewResult = reviewEngine.execute(
                    sessionId,
                    reviewerPool.reviewerFor(startedTask),
                    startedTask,
                    contextPack
            );
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "Review task failed: sessionId=%s taskId=%s reviewer=%s".formatted(
                            sessionId,
                            startedTask.taskId(),
                            reviewerPool.reviewerFor(startedTask).agentName()
                    ),
                    error
            );
        }

        for (Finding finding : reviewResult.findings()) {
            listener.onFindingReported(startedTask, finding);
        }
        ReviewTask completedTask = startedTask.complete();
        listener.onTaskCompleted(completedTask, reviewResult);
        return new TaskExecution(completedTask, reviewResult);
    }

    public record RunResult(
            ReviewPlan reviewPlan,
            ReviewResult reviewResult
    ) {
    }

    public interface Listener {

        default void onPlanReady(ReviewPlan reviewPlan) {
        }

        default void onTaskStarted(ReviewTask reviewTask, ContextPack contextPack) {
        }

        default void onFindingReported(ReviewTask reviewTask, Finding finding) {
        }

        default void onTaskCompleted(ReviewTask reviewTask, ReviewResult reviewResult) {
        }
    }

    private record TaskExecution(
            ReviewTask completedTask,
            ReviewResult reviewResult
    ) {
    }
}
