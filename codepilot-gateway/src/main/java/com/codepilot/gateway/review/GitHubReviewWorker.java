package com.codepilot.gateway.review;

import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.memory.DreamService;
import com.codepilot.core.application.plan.PlanningAgent;
import com.codepilot.core.application.review.MergeAgent;
import com.codepilot.core.application.review.ReviewEngine;
import com.codepilot.core.application.review.ReviewOrchestrator;
import com.codepilot.core.application.review.ReviewerPool;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.application.review.ToolCallParser;
import com.codepilot.core.application.session.SessionStore;
import com.codepilot.core.application.tool.ToolExecutor;
import com.codepilot.core.application.tool.ToolRegistry;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.core.domain.session.SessionEvent;
import com.codepilot.core.infrastructure.tool.AstParseTool;
import com.codepilot.core.infrastructure.tool.MemorySearchTool;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.codepilot.core.infrastructure.tool.SearchPatternTool;
import com.codepilot.gateway.github.GitHubCommentWriter;
import com.codepilot.gateway.github.GitHubPullRequestClient;
import com.codepilot.gateway.github.GitHubPullRequestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class GitHubReviewWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubReviewWorker.class);

    private final RedisStreamReviewEventBuffer eventBuffer;

    private final ReviewSessionRepository reviewSessionRepository;

    private final SessionStore sessionStore;

    private final GitHubPullRequestClient pullRequestClient;

    private final GitHubCommentWriter commentWriter;

    private final ReviewSseBroadcaster sseBroadcaster;

    private final LlmClient llmClient;

    private final ProjectMemoryRepository projectMemoryRepository;

    private final DreamService dreamService;

    private final DiffAnalyzer diffAnalyzer;

    private final ContextCompiler contextCompiler;

    private final ObjectMapper objectMapper;

    private final TokenCounter tokenCounter;

    private final String reviewModel;

    private final Map<String, Object> llmParams;

    private final int maxIterations;

    @Autowired
    GitHubReviewWorker(
            RedisStreamReviewEventBuffer eventBuffer,
            ReviewSessionRepository reviewSessionRepository,
            GitHubPullRequestClient pullRequestClient,
            GitHubCommentWriter commentWriter,
            ReviewSseBroadcaster sseBroadcaster,
            LlmClient llmClient,
            ProjectMemoryRepository projectMemoryRepository,
            DiffAnalyzer diffAnalyzer,
            ContextCompiler contextCompiler,
            ObjectMapper objectMapper,
            TokenCounter tokenCounter,
            @Value("${codepilot.llm.default-model:gpt-4.1-mini}") String reviewModel
    ) {
        this(
                eventBuffer,
                reviewSessionRepository,
                pullRequestClient,
                commentWriter,
                sseBroadcaster,
                llmClient,
                projectMemoryRepository,
                diffAnalyzer,
                contextCompiler,
                objectMapper,
                tokenCounter,
                reviewModel,
                Map.of(),
                6
        );
    }

    public GitHubReviewWorker(
            RedisStreamReviewEventBuffer eventBuffer,
            ReviewSessionRepository reviewSessionRepository,
            GitHubPullRequestClient pullRequestClient,
            GitHubCommentWriter commentWriter,
            ReviewSseBroadcaster sseBroadcaster,
            LlmClient llmClient,
            ProjectMemoryRepository projectMemoryRepository,
            DiffAnalyzer diffAnalyzer,
            ContextCompiler contextCompiler,
            ObjectMapper objectMapper,
            TokenCounter tokenCounter,
            String reviewModel,
            Map<String, Object> llmParams,
            int maxIterations
    ) {
        this.eventBuffer = eventBuffer;
        this.reviewSessionRepository = reviewSessionRepository;
        this.sessionStore = new SessionStore(reviewSessionRepository);
        this.pullRequestClient = pullRequestClient;
        this.commentWriter = commentWriter;
        this.sseBroadcaster = sseBroadcaster;
        this.llmClient = llmClient;
        this.projectMemoryRepository = projectMemoryRepository;
        this.dreamService = new DreamService(projectMemoryRepository);
        this.diffAnalyzer = diffAnalyzer;
        this.contextCompiler = contextCompiler;
        this.objectMapper = objectMapper;
        this.tokenCounter = tokenCounter;
        this.reviewModel = reviewModel;
        this.llmParams = llmParams == null ? Map.of() : Map.copyOf(llmParams);
        this.maxIterations = maxIterations;
    }

    @Scheduled(fixedDelayString = "${codepilot.gateway.worker-delay:2000}")
    public void processPendingEvents() {
        List<RedisStreamReviewEventBuffer.BufferedReviewEvent> pendingEvents = eventBuffer.readPending(10);
        if (pendingEvents.isEmpty()) {
            return;
        }
        LOGGER.info("Read pending GitHub review events count={}", pendingEvents.size());
        for (RedisStreamReviewEventBuffer.BufferedReviewEvent bufferedEvent : pendingEvents) {
            LOGGER.info(
                    "Processing GitHub review event messageId={} sessionId={} repository={} prNumber={} headSha={}",
                    bufferedEvent.messageId(),
                    bufferedEvent.event().sessionId(),
                    bufferedEvent.event().projectId(),
                    bufferedEvent.event().prNumber(),
                    bufferedEvent.event().headSha()
            );
            try {
                process(bufferedEvent.event());
                LOGGER.info(
                        "Processed GitHub review event successfully sessionId={} repository={} prNumber={}",
                        bufferedEvent.event().sessionId(),
                        bufferedEvent.event().projectId(),
                        bufferedEvent.event().prNumber()
                );
            } catch (Exception exception) {
                fail(bufferedEvent.event(), exception);
            } finally {
                eventBuffer.remove(bufferedEvent.messageId());
                LOGGER.info(
                        "Removed GitHub review event from buffer messageId={} sessionId={}",
                        bufferedEvent.messageId(),
                        bufferedEvent.event().sessionId()
                );
            }
        }
    }

    private void process(GitHubPullRequestEvent event) {
        SessionStore.RestoredSession restored = sessionStore.restore(event.sessionId())
                .orElseThrow(() -> new IllegalStateException("Missing ReviewSession %s".formatted(event.sessionId())));
        ReviewSession session = restored.session();
        LOGGER.info(
                "Restored review session sessionId={} state={} hasPlan={} completedTaskResults={} hasReviewResult={}",
                event.sessionId(),
                session.state(),
                session.reviewPlan() != null,
                restored.completedTaskResults().size(),
                session.reviewResult() != null
        );
        if (session.state() == AgentState.DONE) {
            LOGGER.info("Skipping processing because session is already DONE sessionId={}", event.sessionId());
            sseBroadcaster.complete(event.sessionId());
            return;
        }
        if (session.state() == AgentState.FAILED) {
            LOGGER.warn("Refusing to process session already marked FAILED sessionId={}", event.sessionId());
            throw new IllegalStateException("ReviewSession %s is already failed".formatted(event.sessionId()));
        }

        ProjectMemory projectMemory = projectMemoryRepository.findByProjectId(event.projectId())
                .orElseGet(() -> ProjectMemory.empty(event.projectId()));
        String rawDiff = "";
        boolean hasRemainingTasks = hasRemainingTasks(session.reviewPlan());

        if (session.state() == AgentState.IDLE
                || session.state() == AgentState.PLANNING
                || (session.state() == AgentState.REVIEWING
                && (session.reviewPlan() == null || hasRemainingTasks))) {
            LOGGER.info(
                    "Fetching pull request diff sessionId={} repository={} prNumber={}",
                    event.sessionId(),
                    event.projectId(),
                    event.prNumber()
            );
            rawDiff = pullRequestClient.fetchPullRequestDiff(event.owner(), event.repository(), event.prNumber());
            LOGGER.info(
                    "Fetched pull request diff sessionId={} diffChars={}",
                    event.sessionId(),
                    rawDiff.length()
            );
        }

        if (session.state() == AgentState.IDLE) {
            ReviewPlan reviewPlan = new PlanningAgent(diffAnalyzer).plan(event.sessionId(), rawDiff);
            LOGGER.info(
                    "Created review plan from IDLE sessionId={} planId={} strategy={} taskCount={}",
                    event.sessionId(),
                    reviewPlan.planId(),
                    reviewPlan.strategy(),
                    reviewPlan.taskGraph().allTasks().size()
            );
            session = session.startPlanning(reviewPlan.diffSummary(), Instant.now());
            reviewSessionRepository.save(session);
        }

        if (session.state() == AgentState.PLANNING && session.reviewPlan() == null) {
            ReviewPlan reviewPlan = new PlanningAgent(diffAnalyzer).plan(event.sessionId(), rawDiff);
            session = session.attachPlan(reviewPlan, Instant.now());
            reviewSessionRepository.save(session);
            LOGGER.info(
                    "Attached review plan sessionId={} planId={} strategy={} taskCount={}",
                    event.sessionId(),
                    reviewPlan.planId(),
                    reviewPlan.strategy(),
                    reviewPlan.taskGraph().allTasks().size()
            );
            sseBroadcaster.publish(event.sessionId(), "plan_ready", Map.of(
                    "planId", reviewPlan.planId(),
                    "taskCount", reviewPlan.taskGraph().allTasks().size(),
                    "strategy", reviewPlan.strategy().name()
            ));
        }

        if (session.state() == AgentState.PLANNING) {
            session = session.startReviewing(Instant.now());
            reviewSessionRepository.save(session);
            LOGGER.info(
                    "Session entered REVIEWING sessionId={} remainingTasks={}",
                    event.sessionId(),
                    remainingTaskCount(session.reviewPlan())
            );
        }

        ReviewResult reviewResult = session.reviewResult();
        if (session.state() == AgentState.REVIEWING) {
            ReviewPlan effectivePlan = session.reviewPlan();
            if (effectivePlan == null) {
                throw new IllegalStateException("ReviewSession %s cannot resume REVIEWING without ReviewPlan"
                        .formatted(event.sessionId()));
            }

            if (hasRemainingTasks(effectivePlan)) {
                List<GitHubPullRequestClient.PullRequestFileSnapshot> snapshots = pullRequestClient.fetchPullRequestFiles(
                        event.owner(),
                        event.repository(),
                        event.prNumber(),
                        event.headSha()
                );
                LOGGER.info(
                        "Fetched pull request file snapshots sessionId={} fileCount={} remainingTasks={} seedResults={}",
                        event.sessionId(),
                        snapshots.size(),
                        remainingTaskCount(effectivePlan),
                        restored.completedTaskResults().size()
                );
                Path repoRoot = materializeWorkspace(event, snapshots);
                try {
                    LOGGER.info(
                            "Executing review plan sessionId={} workspace={} patterns={} conventions={}",
                            event.sessionId(),
                            repoRoot,
                            projectMemory.reviewPatterns().size(),
                            projectMemory.teamConventions().size()
                    );
                    reviewResult = buildReviewOrchestrator(repoRoot, event.projectId()).execute(
                            effectivePlan,
                            repoRoot,
                            rawDiff,
                            projectMemory,
                            Map.of(
                                    "language", "java",
                                    "entrypoint", "github-webhook",
                                    "repository", event.projectId(),
                                    "headSha", event.headSha()
                            ),
                            restored.completedTaskResults(),
                            new GatewayListener(event.sessionId())
                    ).reviewResult();
                    LOGGER.info(
                            "Review execution completed sessionId={} findingCount={} partial={}",
                            event.sessionId(),
                            reviewResult.findings().size(),
                            reviewResult.partial()
                    );
                } finally {
                    deleteWorkspace(repoRoot);
                }
            } else {
                LOGGER.info(
                        "All review tasks already terminal; merging restored task results sessionId={} completedTaskResults={}",
                        event.sessionId(),
                        restored.completedTaskResults().size()
                );
                reviewResult = new MergeAgent().merge(event.sessionId(), restored.completedTaskResults());
            }
            session = session.startMerging(Instant.now());
            reviewSessionRepository.save(session);
            LOGGER.info("Session advanced to MERGING sessionId={}", event.sessionId());
        }

        if (session.state() == AgentState.MERGING) {
            reviewResult = reviewResult == null
                    ? new MergeAgent().merge(event.sessionId(), restored.completedTaskResults())
                    : reviewResult;
            LOGGER.info(
                    "Merged review result sessionId={} findingCount={} partial={}",
                    event.sessionId(),
                    reviewResult.findings().size(),
                    reviewResult.partial()
            );
            session = session.startReporting(reviewResult, Instant.now());
            reviewSessionRepository.save(session);
            LOGGER.info("Session advanced to REPORTING sessionId={}", event.sessionId());
        }

        if (session.state() == AgentState.REPORTING) {
            reviewResult = reviewResult == null ? session.reviewResult() : reviewResult;
            if (reviewResult == null) {
                throw new IllegalStateException("ReviewSession %s cannot report without ReviewResult"
                        .formatted(event.sessionId()));
            }
            LOGGER.info(
                    "Writing GitHub review comments sessionId={} repository={} prNumber={} findingCount={}",
                    event.sessionId(),
                    event.projectId(),
                    event.prNumber(),
                    reviewResult.findings().size()
            );
            commentWriter.writeReview(event, reviewResult);
            LOGGER.info(
                    "GitHub review comments written sessionId={} repository={} prNumber={}",
                    event.sessionId(),
                    event.projectId(),
                    event.prNumber()
            );
            session = session.markDone(reviewResult, Instant.now());
            reviewSessionRepository.save(session);
            sseBroadcaster.publish(event.sessionId(), "review_completed", Map.of(
                    "sessionId", event.sessionId(),
                    "findingCount", reviewResult.findings().size(),
                    "partial", reviewResult.partial()
            ));
            sseBroadcaster.complete(event.sessionId());
            LOGGER.info(
                    "Review session completed sessionId={} findingCount={} partial={}",
                    event.sessionId(),
                    reviewResult.findings().size(),
                    reviewResult.partial()
            );
            dreamSafely(event.projectId(), reviewResult);
        }
    }

    private boolean hasRemainingTasks(ReviewPlan reviewPlan) {
        return reviewPlan != null && reviewPlan.taskGraph().allTasks().stream().anyMatch(task -> !task.isTerminal());
    }

    private void fail(GitHubPullRequestEvent event, Exception exception) {
        LOGGER.error(
                "GitHub review processing failed sessionId={} repository={} prNumber={} stateTransition=FAILED",
                event.sessionId(),
                event.projectId(),
                event.prNumber(),
                exception
        );
        reviewSessionRepository.findById(event.sessionId()).ifPresent(session -> {
            ReviewSession failed = session.state().terminal()
                    ? session
                    : session.fail(exception.getMessage(), Instant.now());
            reviewSessionRepository.save(failed);
        });
        sseBroadcaster.publish(event.sessionId(), "review_failed", Map.of(
                "sessionId", event.sessionId(),
                "error", exception.getMessage()
        ));
        sseBroadcaster.complete(event.sessionId());
    }

    private ReviewOrchestrator buildReviewOrchestrator(Path repoRoot, String projectId) {
        // Gateway review runs against a temporary PR snapshot workspace instead of a full git clone,
        // so only expose tools that are stable without .git metadata or whole-repo symbol completeness.
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new ReadFileTool(repoRoot),
                new SearchPatternTool(repoRoot),
                new AstParseTool(repoRoot, objectMapper),
                new MemorySearchTool(projectMemoryRepository, projectId)
        ));
        return new ReviewOrchestrator(
                new PlanningAgent(diffAnalyzer),
                contextCompiler,
                new ReviewEngine(
                        llmClient,
                        toolRegistry,
                        new ToolExecutor(toolRegistry),
                        new ToolCallParser(objectMapper),
                        tokenCounter,
                        reviewModel,
                        llmParams,
                        maxIterations
                ),
                new ReviewerPool(),
                new MergeAgent()
        );
    }

    private void dreamSafely(String projectId, ReviewResult reviewResult) {
        try {
            ProjectMemory updated = dreamService.dream(projectId, reviewResult);
            LOGGER.info(
                    "Dream persistence completed projectId={} sessionId={} patterns={} conventions={}",
                    projectId,
                    reviewResult.sessionId(),
                    updated.reviewPatterns().size(),
                    updated.teamConventions().size()
            );
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Dream persistence failed for projectId={} sessionId={}",
                    projectId,
                    reviewResult.sessionId(),
                    exception
            );
        }
    }

    private void appendEvent(String sessionId, SessionEvent.Type type, Map<String, Object> payload) {
        reviewSessionRepository.append(SessionEvent.of(sessionId, type, Instant.now(), payload));
    }

    private int remainingTaskCount(ReviewPlan reviewPlan) {
        if (reviewPlan == null) {
            return 0;
        }
        return (int) reviewPlan.taskGraph().allTasks().stream().filter(task -> !task.isTerminal()).count();
    }

    private Path materializeWorkspace(
            GitHubPullRequestEvent event,
            List<GitHubPullRequestClient.PullRequestFileSnapshot> snapshots
    ) {
        try {
            Path workspace = Files.createTempDirectory("codepilot-" + event.sessionId() + "-");
            for (GitHubPullRequestClient.PullRequestFileSnapshot snapshot : snapshots) {
                if (snapshot.path() == null || snapshot.path().isBlank() || "removed".equalsIgnoreCase(snapshot.status())) {
                    continue;
                }
                Path target = workspace.resolve(snapshot.path()).normalize();
                if (!target.startsWith(workspace)) {
                    throw new IllegalStateException("Refusing to materialize file outside workspace: " + snapshot.path());
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, snapshot.content() == null ? "" : snapshot.content());
            }
            return workspace;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to materialize PR workspace for session " + event.sessionId(), exception);
        }
    }

    private void deleteWorkspace(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return;
        }
        try (var walk = Files.walk(workspace)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Failed to delete workspace " + workspace, exception);
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clean workspace " + workspace, exception);
        }
    }

    private final class GatewayListener implements ReviewOrchestrator.Listener {

        private final String sessionId;

        private GatewayListener(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onTaskStarted(com.codepilot.core.domain.plan.ReviewTask reviewTask, com.codepilot.core.domain.context.ContextPack contextPack) {
            appendEvent(sessionId, SessionEvent.Type.TASK_STARTED, Map.of(
                    "taskId", reviewTask.taskId(),
                    "type", reviewTask.type().name(),
                    "files", reviewTask.targetFiles()
            ));
            sseBroadcaster.publish(sessionId, "task_started", Map.of(
                    "taskId", reviewTask.taskId(),
                    "type", reviewTask.type().name(),
                    "files", reviewTask.targetFiles()
            ));
        }

        @Override
        public void onFindingReported(com.codepilot.core.domain.plan.ReviewTask reviewTask, Finding finding) {
            LOGGER.info(
                    "Finding reported sessionId={} taskId={} severity={} file={} line={} title={}",
                    sessionId,
                    reviewTask.taskId(),
                    finding.severity(),
                    finding.location().filePath(),
                    finding.location().startLine(),
                    finding.title()
            );
            appendEvent(sessionId, SessionEvent.Type.FINDING_REPORTED, Map.ofEntries(
                    Map.entry("findingId", finding.findingId()),
                    Map.entry("taskId", reviewTask.taskId()),
                    Map.entry("category", finding.category()),
                    Map.entry("severity", finding.severity().name()),
                    Map.entry("confidence", finding.confidence()),
                    Map.entry("file", finding.location().filePath()),
                    Map.entry("startLine", finding.location().startLine()),
                    Map.entry("endLine", finding.location().endLine()),
                    Map.entry("title", finding.title()),
                    Map.entry("description", finding.description()),
                    Map.entry("suggestion", finding.suggestion()),
                    Map.entry("evidence", finding.evidence())
            ));
            sseBroadcaster.publish(sessionId, "finding_found", Map.of(
                    "taskId", reviewTask.taskId(),
                    "title", finding.title(),
                    "severity", finding.severity().name(),
                    "file", finding.location().filePath(),
                    "line", finding.location().startLine()
            ));
        }

        @Override
        public void onTaskCompleted(com.codepilot.core.domain.plan.ReviewTask reviewTask, ReviewResult reviewResult) {
            LOGGER.info(
                    "Review task completed sessionId={} taskId={} type={} findingCount={} partial={}",
                    sessionId,
                    reviewTask.taskId(),
                    reviewTask.type(),
                    reviewResult.findings().size(),
                    reviewResult.partial()
            );
            appendEvent(sessionId, SessionEvent.Type.TASK_COMPLETED, Map.of(
                    "taskId", reviewTask.taskId(),
                    "type", reviewTask.type().name(),
                    "findingCount", reviewResult.findings().size(),
                    "partial", reviewResult.partial()
            ));
            sseBroadcaster.publish(sessionId, "task_completed", Map.of(
                    "taskId", reviewTask.taskId(),
                    "type", reviewTask.type().name(),
                    "findingCount", reviewResult.findings().size(),
                    "partial", reviewResult.partial()
            ));
        }
    }
}
