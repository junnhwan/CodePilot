package com.codepilot.eval;

import com.codepilot.core.application.context.DefaultContextCompiler;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.context.ImpactCalculator;
import com.codepilot.core.application.memory.MemoryService;
import com.codepilot.core.application.plan.PlanningAgent;
import com.codepilot.core.application.review.MergeAgent;
import com.codepilot.core.application.review.ReviewEngine;
import com.codepilot.core.application.review.ReviewOrchestrator;
import com.codepilot.core.application.review.ReviewerPool;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.application.review.ToolCallParser;
import com.codepilot.core.application.tool.ToolExecutor;
import com.codepilot.core.application.tool.ToolRegistry;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.codepilot.core.infrastructure.tool.AstFindReferencesTool;
import com.codepilot.core.infrastructure.tool.AstGetCallChainTool;
import com.codepilot.core.infrastructure.tool.AstParseTool;
import com.codepilot.core.infrastructure.tool.MemorySearchTool;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.codepilot.core.infrastructure.tool.SearchPatternTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class EvalRunner {

    private final LlmClient llmClient;

    private final ObjectMapper objectMapper;

    private final DiffAnalyzer diffAnalyzer;

    private final TokenCounter tokenCounter;

    private final String model;

    private final Map<String, Object> llmParams;

    public EvalRunner(LlmClient llmClient) {
        this(llmClient, "codepilot-eval-review", Map.of());
    }

    public EvalRunner(LlmClient llmClient, String model, Map<String, Object> llmParams) {
        this.llmClient = llmClient;
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
        this.diffAnalyzer = new DiffAnalyzer();
        this.tokenCounter = new TokenCounter();
        this.model = model;
        this.llmParams = llmParams == null ? Map.of() : Map.copyOf(llmParams);
    }

    public RunResult run(List<EvalScenario> scenarios) {
        String runId = "eval-run-" + Instant.now().toEpochMilli();
        List<ScenarioResult> scenarioResults = (scenarios == null ? List.<EvalScenario>of() : scenarios).stream()
                .map(scenario -> execute(runId, scenario))
                .toList();
        return new RunResult(runId, scenarioResults, Scorecard.from(runId, scenarioResults));
    }

    private ScenarioResult execute(String runId, EvalScenario scenario) {
        Path repoRoot = null;
        long startedAt = System.nanoTime();
        try {
            repoRoot = materializeScenarioWorkspace(scenario);
            ToolRegistry toolRegistry = toolRegistry(repoRoot, scenario.projectMemory());
            ReviewOrchestrator orchestrator = orchestrator(toolRegistry, scenario.stopPolicy().maxIterations());
            RunCapture runCapture = new RunCapture();
            ReviewOrchestrator.RunResult runResult = orchestrator.run(
                    runId + "-" + scenario.scenarioId(),
                    repoRoot,
                    scenario.rawDiff(),
                    scenario.projectMemory(),
                    structuredFacts(scenario),
                    runCapture
            );
            long durationMillis = durationMillis(startedAt);
            return new ScenarioResult(
                    scenario,
                    runCapture.reviewPlan(),
                    runResult.reviewResult(),
                    durationMillis,
                    runCapture.contextTokensUsed(),
                    null
            );
        } catch (RuntimeException | IOException error) {
            return new ScenarioResult(
                    scenario,
                    null,
                    null,
                    durationMillis(startedAt),
                    0,
                    "Eval scenario failed: scenarioId=%s, message=%s".formatted(scenario.scenarioId(), error.getMessage())
            );
        } finally {
            deleteQuietly(repoRoot);
        }
    }

    private ReviewOrchestrator orchestrator(ToolRegistry toolRegistry, int maxIterations) {
        return new ReviewOrchestrator(
                new PlanningAgent(diffAnalyzer),
                new DefaultContextCompiler(
                        diffAnalyzer,
                        new JavaParserAstParser(),
                        new ImpactCalculator(),
                        tokenCounter,
                        new ClasspathCompilationStrategyLoader(objectMapper).load("java-springboot-maven"),
                        new MemoryService(tokenCounter)
                ),
                new ReviewEngine(
                        llmClient,
                        toolRegistry,
                        new ToolExecutor(toolRegistry),
                        new ToolCallParser(objectMapper),
                        tokenCounter,
                        model,
                        llmParams,
                        maxIterations
                ),
                new ReviewerPool(),
                new MergeAgent()
        );
    }

    private ToolRegistry toolRegistry(Path repoRoot, ProjectMemory projectMemory) {
        ProjectMemoryRepository projectMemoryRepository = new FixedProjectMemoryRepository(projectMemory);

        // Eval fixtures only materialize the post-change tree, so P10 keeps the runnable tool set
        // to filesystem/AST/memory tools instead of pretending git history is available.
        return new ToolRegistry(List.of(
                new ReadFileTool(repoRoot),
                new SearchPatternTool(repoRoot),
                new AstParseTool(repoRoot, objectMapper),
                new AstFindReferencesTool(repoRoot, objectMapper, new JavaParserAstParser()),
                new AstGetCallChainTool(repoRoot, objectMapper, new JavaParserAstParser()),
                new MemorySearchTool(projectMemoryRepository, projectMemory.projectId())
        ));
    }

    private Path materializeScenarioWorkspace(EvalScenario scenario) throws IOException {
        Path repoRoot = Files.createTempDirectory("codepilot-eval-" + scenario.scenarioId() + "-");
        for (EvalScenario.RepositoryFile repositoryFile : scenario.repositoryFiles()) {
            Path targetFile = repoRoot.resolve(repositoryFile.path()).normalize();
            if (!targetFile.startsWith(repoRoot)) {
                throw new IllegalStateException("Scenario file escapes repo root: " + repositoryFile.path());
            }
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, repositoryFile.content());
        }
        return repoRoot;
    }

    private Map<String, String> structuredFacts(EvalScenario scenario) {
        Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("entrypoint", "eval");
        facts.putAll(scenario.structuredFacts());
        return Map.copyOf(facts);
    }

    private long durationMillis(long startedAt) {
        return Math.max((System.nanoTime() - startedAt) / 1_000_000L, 0L);
    }

    private void deleteQuietly(Path repoRoot) {
        if (repoRoot == null || !Files.exists(repoRoot)) {
            return;
        }
        try (var paths = Files.walk(repoRoot)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best-effort cleanup for ephemeral eval workspaces.
                        }
                    });
        } catch (IOException ignored) {
            // Best-effort cleanup for ephemeral eval workspaces.
        }
    }

    public record RunResult(
            String evalRunId,
            List<ScenarioResult> scenarioResults,
            Scorecard scorecard
    ) {

        public RunResult {
            evalRunId = requireText(evalRunId, "evalRunId");
            scenarioResults = scenarioResults == null ? List.of() : List.copyOf(scenarioResults);
            if (scorecard == null) {
                throw new IllegalArgumentException("scorecard must not be null");
            }
        }
    }

    public record ScenarioResult(
            EvalScenario scenario,
            ReviewPlan reviewPlan,
            ReviewResult reviewResult,
            long durationMillis,
            int contextTokensUsed,
            String errorMessage
    ) {

        public ScenarioResult {
            if (scenario == null) {
                throw new IllegalArgumentException("scenario must not be null");
            }
            errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
        }

        public boolean successful() {
            return errorMessage == null;
        }

        public boolean partial() {
            return successful() && reviewResult != null && reviewResult.partial();
        }

        public Evaluation evaluation() {
            if (!successful() || reviewResult == null) {
                return new Evaluation(List.of(), scenario.groundTruth(), List.of());
            }

            List<EvalScenario.GroundTruthFinding> remaining = new ArrayList<>(scenario.groundTruth());
            List<EvalScenario.GroundTruthFinding> matched = new ArrayList<>();
            List<Finding> falsePositives = new ArrayList<>();

            for (Finding finding : reviewResult.findings()) {
                EvalScenario.GroundTruthFinding hit = remaining.stream()
                        .filter(groundTruthFinding -> groundTruthFinding.matches(finding))
                        .findFirst()
                        .orElse(null);
                if (hit == null) {
                    falsePositives.add(finding);
                    continue;
                }
                matched.add(hit);
                remaining.remove(hit);
            }

            return new Evaluation(
                    List.copyOf(matched),
                    List.copyOf(remaining),
                    List.copyOf(falsePositives)
            );
        }

        public boolean passed() {
            Evaluation evaluation = evaluation();
            return successful()
                    && evaluation.missedGroundTruth().isEmpty()
                    && evaluation.falsePositives().isEmpty();
        }

        public record Evaluation(
                List<EvalScenario.GroundTruthFinding> matchedGroundTruth,
                List<EvalScenario.GroundTruthFinding> missedGroundTruth,
                List<Finding> falsePositives
        ) {

            public Evaluation {
                matchedGroundTruth = matchedGroundTruth == null ? List.of() : List.copyOf(matchedGroundTruth);
                missedGroundTruth = missedGroundTruth == null ? List.of() : List.copyOf(missedGroundTruth);
                falsePositives = falsePositives == null ? List.of() : List.copyOf(falsePositives);
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private record RunCapture(
            java.util.concurrent.atomic.AtomicReference<ReviewPlan> reviewPlanRef,
            java.util.concurrent.atomic.AtomicInteger contextTokensUsedRef
    ) implements ReviewOrchestrator.Listener {

        private RunCapture() {
            this(new java.util.concurrent.atomic.AtomicReference<>(), new java.util.concurrent.atomic.AtomicInteger());
        }

        @Override
        public void onPlanReady(ReviewPlan reviewPlan) {
            reviewPlanRef.compareAndSet(null, reviewPlan);
        }

        @Override
        public void onTaskStarted(com.codepilot.core.domain.plan.ReviewTask reviewTask, ContextPack contextPack) {
            if (contextPack != null) {
                contextTokensUsedRef.compareAndSet(0, contextPack.tokenBudget().usedTokens());
            }
        }

        private ReviewPlan reviewPlan() {
            return reviewPlanRef.get();
        }

        private int contextTokensUsed() {
            return contextTokensUsedRef.get();
        }
    }

    private record FixedProjectMemoryRepository(
            ProjectMemory projectMemory
    ) implements ProjectMemoryRepository {

        @Override
        public java.util.Optional<ProjectMemory> findByProjectId(String projectId) {
            if (projectMemory == null || !projectMemory.projectId().equals(projectId)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(projectMemory);
        }

        @Override
        public void save(ProjectMemory projectMemory) {
            throw new UnsupportedOperationException("EvalRunner does not persist project memory");
        }
    }
}
