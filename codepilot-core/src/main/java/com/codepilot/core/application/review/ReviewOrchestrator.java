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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class ReviewOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewOrchestrator.class);

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
        TaskGraph taskGraph = reviewPlan.taskGraph();
        List<ReviewResult> taskResults = new ArrayList<>(seedResults);
        LOGGER.info(
                "Starting review orchestration sessionId={} planId={} strategy={} totalTasks={} seedResults={}",
                sessionId,
                reviewPlan.planId(),
                reviewPlan.strategy(),
                taskGraph.allTasks().size(),
                taskResults.size()
        );
        if (taskGraph.allTasks().stream().noneMatch(task -> !task.isTerminal())) {
            LOGGER.info(
                    "All review tasks already terminal before orchestration sessionId={} seedResults={}",
                    sessionId,
                    taskResults.size()
            );
            ReviewResult mergedResult = mergeAgent.merge(sessionId, taskResults);
            LOGGER.info(
                    "Merged orchestration result without rerunning tasks sessionId={} findingCount={} partial={}",
                    sessionId,
                    mergedResult.findings().size(),
                    mergedResult.partial()
            );
            return new RunResult(reviewPlan, mergedResult);
        }

        ContextPack contextPack = contextCompiler.compile(
                repoRoot.toAbsolutePath().normalize(),
                rawDiff,
                projectMemory == null ? ProjectMemory.empty(sessionId) : projectMemory,
                structuredFacts == null ? Map.of() : structuredFacts
        );
        LOGGER.info(
                "Compiled review context sessionId={} snippets={} patterns={} conventions={} globalKnowledge={} tokenUsage={}/{} reserved={}",
                sessionId,
                contextPack.snippets().size(),
                contextPack.projectMemory().reviewPatterns().size(),
                contextPack.projectMemory().teamConventions().size(),
                contextPack.globalKnowledge().size(),
                contextPack.tokenBudget().usedTokens(),
                contextPack.tokenBudget().totalTokens(),
                contextPack.tokenBudget().reservedTokens()
        );

        while (taskGraph.allTasks().stream().anyMatch(task -> !task.isTerminal())) {
            List<ReviewTask> availableTasks = taskGraph.availableTasks();
            if (availableTasks.isEmpty()) {
                throw new IllegalStateException("ReviewPlan[%s] has no schedulable tasks remaining".formatted(reviewPlan.planId()));
            }
            LOGGER.info(
                "Dispatching review wave sessionId={} readyTasks={} remainingTasks={}",
                sessionId,
                summarizeTasks(availableTasks),
                remainingTaskCount(taskGraph)
            );

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
                LOGGER.info(
                        "Collected review task result sessionId={} taskId={} findings={} partial={}",
                        sessionId,
                        taskExecution.completedTask().taskId(),
                        taskExecution.reviewResult().findings().size(),
                        taskExecution.reviewResult().partial()
                );
            }
        }

        ReviewResult mergedResult = mergeAgent.merge(sessionId, taskResults);
        LOGGER.info(
                "Merged orchestration result sessionId={} taskResultCount={} findingCount={} partial={}",
                sessionId,
                taskResults.size(),
                mergedResult.findings().size(),
                mergedResult.partial()
        );
        return new RunResult(reviewPlan, mergedResult);
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

    private int remainingTaskCount(TaskGraph taskGraph) {
        return (int) taskGraph.allTasks().stream().filter(task -> !task.isTerminal()).count();
    }

    private List<String> summarizeTasks(List<ReviewTask> reviewTasks) {
        return reviewTasks.stream()
                .map(task -> task.taskId() + ":" + task.type())
                .toList();
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
