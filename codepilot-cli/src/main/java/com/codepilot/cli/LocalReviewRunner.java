package com.codepilot.cli;

import com.codepilot.core.application.context.DefaultContextCompiler;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.context.ImpactCalculator;
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
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.codepilot.core.infrastructure.tool.AstParseTool;
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
import java.util.Set;

public final class LocalReviewRunner {

    private final LlmClient llmClient;

    private final ObjectMapper objectMapper;

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
        this.tokenCounter = new TokenCounter();
        this.contextCompiler = new DefaultContextCompiler(
                new DiffAnalyzer(),
                new JavaParserAstParser(),
                new ImpactCalculator(),
                tokenCounter,
                new ClasspathCompilationStrategyLoader(objectMapper).load("java-springboot-maven")
        );
        this.model = model;
        this.llmParams = llmParams == null ? Map.of() : Map.copyOf(llmParams);
        this.maxIterations = maxIterations;
    }

    public ReviewResult run(Path diffFile, Path repoRoot) {
        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        String rawDiff = readDiff(diffFile);
        ContextPack contextPack = contextCompiler.compile(
                normalizedRepoRoot,
                rawDiff,
                ProjectMemory.empty("cli-project"),
                Map.of("language", "java", "entrypoint", "cli")
        );

        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new ReadFileTool(normalizedRepoRoot),
                new SearchPatternTool(normalizedRepoRoot),
                new AstParseTool(normalizedRepoRoot, objectMapper)
        ));

        ReviewEngine reviewEngine = new ReviewEngine(
                llmClient,
                toolRegistry,
                new ToolExecutor(toolRegistry),
                new ToolCallParser(objectMapper),
                tokenCounter,
                model,
                llmParams,
                maxIterations
        );

        ReviewTask reviewTask = ReviewTask.pending(
                "task-security",
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                contextPack.diffSummary().changedFiles().stream().map(changedFile -> changedFile.path()).toList(),
                List.of("Inspect changed Java code for injection, unsafe input handling, and authorization gaps."),
                List.of()
        );

        return reviewEngine.execute(
                "cli-session-" + Instant.now().toEpochMilli(),
                new AgentDefinition(
                        "security-reviewer",
                        "Review changed code for high-signal security defects.",
                        Set.of(AgentState.REVIEWING),
                        Set.of(AgentDecision.Type.CALL_TOOL, AgentDecision.Type.DELIVER),
                        List.of("SQL injection", "unsafe input handling", "missing authorization")
                ),
                reviewTask,
                contextPack
        );
    }

    private String readDiff(Path diffFile) {
        try {
            return Files.readString(diffFile);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read diff file " + diffFile, error);
        }
    }
}
