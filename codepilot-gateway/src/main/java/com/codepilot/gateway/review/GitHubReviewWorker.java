package com.codepilot.gateway.review;

import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.review.ReviewEngine;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.application.review.ToolCallParser;
import com.codepilot.core.application.tool.ToolExecutor;
import com.codepilot.core.application.tool.ToolRegistry;
import com.codepilot.core.domain.agent.AgentDecision;
import com.codepilot.core.domain.agent.AgentDefinition;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.plan.TaskGraph;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.core.domain.session.SessionEvent;
import com.codepilot.core.infrastructure.tool.AstParseTool;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.codepilot.core.infrastructure.tool.SearchPatternTool;
import com.codepilot.gateway.github.GitHubCommentWriter;
import com.codepilot.gateway.github.GitHubPullRequestClient;
import com.codepilot.gateway.github.GitHubPullRequestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GitHubReviewWorker {

    private static final AgentDefinition SECURITY_AGENT = new AgentDefinition(
            "security-reviewer",
            "Review changed code for high-signal security defects.",
            Set.of(AgentState.REVIEWING),
            Set.of(AgentDecision.Type.CALL_TOOL, AgentDecision.Type.DELIVER),
            List.of("SQL injection", "unsafe input handling", "missing authorization")
    );

    private final RedisStreamReviewEventBuffer eventBuffer;

    private final ReviewSessionRepository reviewSessionRepository;

    private final GitHubPullRequestClient pullRequestClient;

    private final GitHubCommentWriter commentWriter;

    private final ReviewSseBroadcaster sseBroadcaster;

    private final LlmClient llmClient;

    private final DiffAnalyzer diffAnalyzer;

    private final ContextCompiler contextCompiler;

    private final ObjectMapper objectMapper;

    private final TokenCounter tokenCounter;

    private final String reviewModel;

    private final Map<String, Object> llmParams;

    private final int maxIterations;

    public GitHubReviewWorker(
            RedisStreamReviewEventBuffer eventBuffer,
            ReviewSessionRepository reviewSessionRepository,
            GitHubPullRequestClient pullRequestClient,
            GitHubCommentWriter commentWriter,
            ReviewSseBroadcaster sseBroadcaster,
            LlmClient llmClient,
            DiffAnalyzer diffAnalyzer,
            ContextCompiler contextCompiler,
            ObjectMapper objectMapper,
            TokenCounter tokenCounter
    ) {
        this(
                eventBuffer,
                reviewSessionRepository,
                pullRequestClient,
                commentWriter,
                sseBroadcaster,
                llmClient,
                diffAnalyzer,
                contextCompiler,
                objectMapper,
                tokenCounter,
                "codepilot-gateway-review",
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
        this.pullRequestClient = pullRequestClient;
        this.commentWriter = commentWriter;
        this.sseBroadcaster = sseBroadcaster;
        this.llmClient = llmClient;
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
        for (RedisStreamReviewEventBuffer.BufferedReviewEvent bufferedEvent : eventBuffer.readPending(10)) {
            try {
                process(bufferedEvent.event());
            } catch (Exception exception) {
                fail(bufferedEvent.event(), exception);
            } finally {
                eventBuffer.remove(bufferedEvent.messageId());
            }
        }
    }

    private void process(GitHubPullRequestEvent event) {
        ReviewSession session = reviewSessionRepository.findById(event.sessionId())
                .orElseThrow(() -> new IllegalStateException("Missing ReviewSession %s".formatted(event.sessionId())));
        String rawDiff = pullRequestClient.fetchPullRequestDiff(event.owner(), event.repository(), event.prNumber());
        DiffAnalyzer.DiffAnalysis diffAnalysis = diffAnalyzer.analyze(rawDiff);
        DiffSummary diffSummary = toDiffSummary(diffAnalysis);
        ReviewTask plannedTask = buildTask(diffSummary);
        ReviewPlan reviewPlan = new ReviewPlan(
                "plan-" + event.sessionId(),
                event.sessionId(),
                diffSummary,
                TaskGraph.of(List.of(plannedTask)),
                ReviewPlan.ReviewStrategy.SECURITY_FIRST
        );

        Instant now = Instant.now();
        session = session.startPlanning(diffSummary, now);
        reviewSessionRepository.save(session);
        session = session.attachPlan(reviewPlan, Instant.now());
        reviewSessionRepository.save(session);
        sseBroadcaster.publish(event.sessionId(), "plan_ready", Map.of(
                "planId", reviewPlan.planId(),
                "taskCount", 1
        ));

        session = session.startReviewing(Instant.now());
        reviewSessionRepository.save(session);
        ReviewTask activeTask = plannedTask.markReady().start();
        appendEvent(event.sessionId(), SessionEvent.Type.TASK_STARTED, Map.of(
                "taskId", activeTask.taskId(),
                "type", activeTask.type().name(),
                "files", activeTask.targetFiles()
        ));
        sseBroadcaster.publish(event.sessionId(), "task_started", Map.of(
                "taskId", activeTask.taskId(),
                "type", activeTask.type().name(),
                "files", activeTask.targetFiles()
        ));

        Path repoRoot = materializeWorkspace(event, pullRequestClient.fetchPullRequestFiles(
                event.owner(),
                event.repository(),
                event.prNumber(),
                event.headSha()
        ));
        try {
            ContextPack contextPack = contextCompiler.compile(
                    repoRoot,
                    rawDiff,
                    com.codepilot.core.domain.memory.ProjectMemory.empty(event.projectId()),
                    Map.of(
                            "language", "java",
                            "entrypoint", "github-webhook",
                            "repository", event.projectId(),
                            "headSha", event.headSha()
                    )
            );
            ReviewResult reviewResult = buildReviewEngine(repoRoot).execute(
                    event.sessionId(),
                    SECURITY_AGENT,
                    activeTask,
                    contextPack
            );

            for (Finding finding : reviewResult.findings()) {
                appendEvent(event.sessionId(), SessionEvent.Type.FINDING_REPORTED, Map.of(
                        "taskId", activeTask.taskId(),
                        "title", finding.title(),
                        "severity", finding.severity().name(),
                        "file", finding.location().filePath(),
                        "line", finding.location().startLine()
                ));
                sseBroadcaster.publish(event.sessionId(), "finding_found", Map.of(
                        "taskId", activeTask.taskId(),
                        "title", finding.title(),
                        "severity", finding.severity().name(),
                        "file", finding.location().filePath(),
                        "line", finding.location().startLine()
                ));
            }

            appendEvent(event.sessionId(), SessionEvent.Type.TASK_COMPLETED, Map.of(
                    "taskId", activeTask.taskId(),
                    "findingCount", reviewResult.findings().size()
            ));
            sseBroadcaster.publish(event.sessionId(), "task_completed", Map.of(
                    "taskId", activeTask.taskId(),
                    "findingCount", reviewResult.findings().size()
            ));

            session = session.startMerging(Instant.now());
            reviewSessionRepository.save(session);
            session = session.startReporting(reviewResult, Instant.now());
            reviewSessionRepository.save(session);
            commentWriter.writeReview(event, reviewResult);
            session = session.markDone(reviewResult, Instant.now());
            reviewSessionRepository.save(session);
            sseBroadcaster.publish(event.sessionId(), "review_completed", Map.of(
                    "sessionId", event.sessionId(),
                    "findingCount", reviewResult.findings().size(),
                    "partial", reviewResult.partial()
            ));
            sseBroadcaster.complete(event.sessionId());
        } finally {
            deleteWorkspace(repoRoot);
        }
    }

    private void fail(GitHubPullRequestEvent event, Exception exception) {
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

    private ReviewTask buildTask(DiffSummary diffSummary) {
        List<String> changedFiles = diffSummary.changedFiles().stream()
                .map(DiffSummary.ChangedFile::path)
                .toList();
        return ReviewTask.pending(
                "task-security",
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                changedFiles,
                List.of("Inspect changed code for high-confidence security defects."),
                List.of()
        );
    }

    private ReviewEngine buildReviewEngine(Path repoRoot) {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new ReadFileTool(repoRoot),
                new SearchPatternTool(repoRoot),
                new AstParseTool(repoRoot, objectMapper)
        ));
        return new ReviewEngine(
                llmClient,
                toolRegistry,
                new ToolExecutor(toolRegistry),
                new ToolCallParser(objectMapper),
                tokenCounter,
                reviewModel,
                llmParams,
                maxIterations
        );
    }

    private void appendEvent(String sessionId, SessionEvent.Type type, Map<String, Object> payload) {
        reviewSessionRepository.append(SessionEvent.of(sessionId, type, Instant.now(), payload));
    }

    private DiffSummary toDiffSummary(DiffAnalyzer.DiffAnalysis diffAnalysis) {
        return DiffSummary.of(diffAnalysis.fileDeltas().stream()
                .map(fileDelta -> new DiffSummary.ChangedFile(
                        fileDelta.path(),
                        fileDelta.changeType(),
                        fileDelta.additions(),
                        fileDelta.deletions(),
                        List.of()
                ))
                .toList());
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
}
