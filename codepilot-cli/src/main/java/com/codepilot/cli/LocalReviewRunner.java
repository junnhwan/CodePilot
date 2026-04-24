package com.codepilot.cli;

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
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
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
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LocalReviewRunner {

    private final LlmClient llmClient;

    private final ObjectMapper objectMapper;

    private final DiffAnalyzer diffAnalyzer;

    private final TokenCounter tokenCounter;

    private final ContextCompiler contextCompiler;

    private final String model;

    private final Map<String, Object> llmParams;

    private final int maxIterations;

    public LocalReviewRunner(LlmClient llmClient) {
        this(llmClient, "codepilot-cli-review", Map.of(), 6);
    }

    public LocalReviewRunner(LlmClient llmClient, String model, Map<String, Object> llmParams, int maxIterations) {
        this.llmClient = llmClient;
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
        this.diffAnalyzer = new DiffAnalyzer();
        this.tokenCounter = new TokenCounter();
        this.contextCompiler = new DefaultContextCompiler(
                diffAnalyzer,
                new JavaParserAstParser(),
                new ImpactCalculator(),
                tokenCounter,
                new ClasspathCompilationStrategyLoader(objectMapper).load("java-springboot-maven"),
                new MemoryService(tokenCounter)
        );
        this.model = model;
        this.llmParams = llmParams == null ? Map.of() : Map.copyOf(llmParams);
        this.maxIterations = maxIterations;
    }

    public ReviewResult run(Path diffFile, Path repoRoot) {
        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        String rawDiff = readDiff(diffFile);

        ProjectMemoryRepository projectMemoryRepository = new ProjectMemoryRepository() {
            @Override
            public Optional<ProjectMemory> findByProjectId(String projectId) {
                return Optional.empty();
            }

            @Override
            public void save(ProjectMemory projectMemory) {
                throw new UnsupportedOperationException("LocalReviewRunner does not persist project memory");
            }
        };

        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new ReadFileTool(normalizedRepoRoot),
                new SearchPatternTool(normalizedRepoRoot),
                new AstParseTool(normalizedRepoRoot, objectMapper),
                new GitBlameTool(normalizedRepoRoot),
                new GitLogTool(normalizedRepoRoot),
                new GitDiffContextTool(normalizedRepoRoot),
                new AstFindReferencesTool(normalizedRepoRoot, objectMapper, new JavaParserAstParser()),
                new AstGetCallChainTool(normalizedRepoRoot, objectMapper, new JavaParserAstParser()),
                new MemorySearchTool(projectMemoryRepository, "cli-project")
        ));

        ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                new PlanningAgent(diffAnalyzer),
                contextCompiler,
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

        return orchestrator.run(
                "cli-session-" + Instant.now().toEpochMilli(),
                normalizedRepoRoot,
                rawDiff,
                ProjectMemory.empty("cli-project"),
                Map.of("language", "java", "entrypoint", "cli")
        ).reviewResult();
    }

    private String readDiff(Path diffFile) {
        try {
            return Files.readString(diffFile);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read diff file " + diffFile, error);
        }
    }
}
