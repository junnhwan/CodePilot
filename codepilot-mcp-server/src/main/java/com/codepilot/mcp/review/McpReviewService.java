package com.codepilot.mcp.review;

import com.codepilot.core.application.plan.PlanningAgent;
import com.codepilot.core.application.review.MergeAgent;
import com.codepilot.core.application.review.ReviewEngine;
import com.codepilot.core.application.review.ReviewOrchestrator;
import com.codepilot.core.application.review.ReviewerPool;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.application.review.ToolCallParser;
import com.codepilot.core.application.tool.ToolExecutor;
import com.codepilot.core.application.tool.ToolRegistry;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.infrastructure.tool.AstFindReferencesTool;
import com.codepilot.core.infrastructure.tool.AstGetCallChainTool;
import com.codepilot.core.infrastructure.tool.AstParseTool;
import com.codepilot.core.infrastructure.tool.GitBlameTool;
import com.codepilot.core.infrastructure.tool.GitDiffContextTool;
import com.codepilot.core.infrastructure.tool.GitLogTool;
import com.codepilot.core.infrastructure.tool.MemorySearchTool;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.codepilot.core.infrastructure.tool.SearchPatternTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class McpReviewService {

    private final LlmClient llmClient;

    private final ProjectMemoryRepository projectMemoryRepository;

    private final com.codepilot.core.application.context.DiffAnalyzer diffAnalyzer;

    private final ContextCompiler contextCompiler;

    private final ObjectMapper objectMapper;

    private final TokenCounter tokenCounter;

    private final String reviewModel;

    private final Map<String, Object> llmParams;

    private final int maxIterations;

    public McpReviewService(
            LlmClient llmClient,
            ProjectMemoryRepository projectMemoryRepository,
            com.codepilot.core.application.context.DiffAnalyzer diffAnalyzer,
            ContextCompiler contextCompiler,
            ObjectMapper objectMapper,
            TokenCounter tokenCounter,
            String reviewModel,
            Map<String, Object> llmParams,
            int maxIterations
    ) {
        this.llmClient = llmClient;
        this.projectMemoryRepository = projectMemoryRepository;
        this.diffAnalyzer = diffAnalyzer;
        this.contextCompiler = contextCompiler;
        this.objectMapper = objectMapper;
        this.tokenCounter = tokenCounter;
        this.reviewModel = reviewModel;
        this.llmParams = llmParams == null ? Map.of() : Map.copyOf(llmParams);
        this.maxIterations = maxIterations;
    }

    public ReviewResponse reviewDiff(ReviewDiffRequest request) {
        Path normalizedRepoRoot = normalizedRepoRoot(request.repoRoot());
        ReviewOrchestrator orchestrator = buildReviewOrchestrator(normalizedRepoRoot, request.projectId());
        ReviewOrchestrator.RunResult runResult = orchestrator.run(
                nextSessionId(),
                normalizedRepoRoot,
                request.rawDiff(),
                projectMemory(request.projectId()),
                structuredFacts(request.structuredFacts(), "mcp-review-diff")
        );
        return new ReviewResponse(runResult.reviewPlan().sessionId(), request.projectId(), runResult.reviewPlan(), runResult.reviewResult());
    }

    public ReviewResponse reviewPr(ReviewPrRequest request, GitHubPullRequestClient gitHubPullRequestClient) {
        String headSha = gitHubPullRequestClient.fetchHeadSha(
                request.apiBaseUrl(),
                request.apiToken(),
                request.owner(),
                request.repository(),
                request.prNumber()
        );
        String rawDiff = gitHubPullRequestClient.fetchPullRequestDiff(
                request.apiBaseUrl(),
                request.apiToken(),
                request.owner(),
                request.repository(),
                request.prNumber()
        );
        List<GitHubPullRequestClient.PullRequestFileSnapshot> snapshots = gitHubPullRequestClient.fetchPullRequestFiles(
                request.apiBaseUrl(),
                request.apiToken(),
                request.owner(),
                request.repository(),
                request.prNumber(),
                headSha
        );
        Path workspace = materializeWorkspace(snapshots);
        try {
            ReviewOrchestrator orchestrator = buildReviewOrchestrator(workspace, request.projectId());
            ReviewOrchestrator.RunResult runResult = orchestrator.run(
                    nextSessionId(),
                    workspace,
                    rawDiff,
                    projectMemory(request.projectId()),
                    structuredFacts(Map.of(
                            "entrypoint", "mcp-review-pr",
                            "owner", request.owner(),
                            "repository", request.repository(),
                            "prNumber", Integer.toString(request.prNumber())
                    ), "mcp-review-pr")
            );
            return new ReviewResponse(runResult.reviewPlan().sessionId(), request.projectId(), runResult.reviewPlan(), runResult.reviewResult());
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private ReviewOrchestrator buildReviewOrchestrator(Path repoRoot, String projectId) {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new ReadFileTool(repoRoot),
                new SearchPatternTool(repoRoot),
                new AstParseTool(repoRoot, objectMapper),
                new GitBlameTool(repoRoot),
                new GitLogTool(repoRoot),
                new GitDiffContextTool(repoRoot),
                new AstFindReferencesTool(repoRoot, objectMapper, new com.codepilot.core.infrastructure.context.JavaParserAstParser()),
                new AstGetCallChainTool(repoRoot, objectMapper, new com.codepilot.core.infrastructure.context.JavaParserAstParser()),
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

    private ProjectMemory projectMemory(String projectId) {
        return projectMemoryRepository.findByProjectId(projectId)
                .orElseGet(() -> ProjectMemory.empty(projectId));
    }

    private Map<String, String> structuredFacts(Map<String, String> rawFacts, String entrypoint) {
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("entrypoint", entrypoint);
        facts.put("language", "java");
        if (rawFacts != null) {
            facts.putAll(rawFacts);
        }
        return Map.copyOf(facts);
    }

    private Path normalizedRepoRoot(Path repoRoot) {
        Path normalized = repoRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("repo_root must point to an existing directory: " + normalized);
        }
        return normalized;
    }

    private Path materializeWorkspace(List<GitHubPullRequestClient.PullRequestFileSnapshot> snapshots) {
        try {
            Path workspace = Files.createTempDirectory("codepilot-mcp-review-");
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
            throw new IllegalStateException("Failed to materialize pull request workspace", exception);
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

    private String nextSessionId() {
        return "mcp-review-" + UUID.randomUUID();
    }

    public record ReviewDiffRequest(
            Path repoRoot,
            String rawDiff,
            String projectId,
            Map<String, String> structuredFacts
    ) {

        public ReviewDiffRequest {
            if (repoRoot == null) {
                throw new IllegalArgumentException("repo_root must not be null");
            }
            rawDiff = requireText(rawDiff, "raw_diff");
            projectId = requireText(projectId, "project_id");
            structuredFacts = structuredFacts == null ? Map.of() : Map.copyOf(structuredFacts);
        }
    }

    public record ReviewPrRequest(
            String owner,
            String repository,
            int prNumber,
            String projectId,
            String apiBaseUrl,
            String apiToken
    ) {

        public ReviewPrRequest {
            owner = requireText(owner, "owner");
            repository = requireText(repository, "repository");
            if (prNumber <= 0) {
                throw new IllegalArgumentException("pr_number must be positive");
            }
            projectId = requireText(projectId, "project_id");
            apiBaseUrl = requireText(apiBaseUrl, "api_base_url");
            apiToken = apiToken == null ? "" : apiToken.trim();
        }
    }

    public record ReviewResponse(
            String sessionId,
            String projectId,
            ReviewPlan reviewPlan,
            ReviewResult reviewResult
    ) {

        public ReviewResponse {
            sessionId = requireText(sessionId, "sessionId");
            projectId = requireText(projectId, "projectId");
            if (reviewPlan == null) {
                throw new IllegalArgumentException("reviewPlan must not be null");
            }
            if (reviewResult == null) {
                throw new IllegalArgumentException("reviewResult must not be null");
            }
        }

        public Map<String, Object> structuredContent() {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("session_id", sessionId);
            payload.put("project_id", projectId);
            payload.put("strategy", reviewPlan.strategy().name());
            payload.put("task_count", reviewPlan.taskGraph().allTasks().size());
            payload.put("finding_count", reviewResult.findings().size());
            payload.put("partial", reviewResult.partial());
            payload.put("findings", reviewResult.findings().stream()
                    .map(ReviewResponse::findingPayload)
                    .toList());
            return Map.copyOf(payload);
        }

        public String textSummary(String toolName) {
            return "%s completed: project_id=%s strategy=%s finding_count=%d partial=%s".formatted(
                    toolName,
                    projectId,
                    reviewPlan.strategy().name(),
                    reviewResult.findings().size(),
                    reviewResult.partial()
            );
        }

        private static Map<String, Object> findingPayload(Finding finding) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("finding_id", finding.findingId());
            payload.put("task_id", finding.taskId());
            payload.put("category", finding.category());
            payload.put("severity", finding.severity().name());
            payload.put("confidence", finding.confidence());
            payload.put("status", finding.status().name());
            payload.put("file", finding.location().filePath());
            payload.put("start_line", finding.location().startLine());
            payload.put("end_line", finding.location().endLine());
            payload.put("title", finding.title());
            payload.put("description", finding.description());
            payload.put("suggestion", finding.suggestion());
            payload.put("evidence", List.copyOf(finding.evidence()));
            return Map.copyOf(payload);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
