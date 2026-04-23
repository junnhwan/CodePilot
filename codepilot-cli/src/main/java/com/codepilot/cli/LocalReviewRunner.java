package com.codepilot.cli;

import com.codepilot.core.application.review.ReviewEngine;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.application.review.ToolCallParser;
import com.codepilot.core.application.tool.ToolExecutor;
import com.codepilot.core.application.tool.ToolRegistry;
import com.codepilot.core.domain.agent.AgentDecision;
import com.codepilot.core.domain.agent.AgentDefinition;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.context.ImpactSet;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.infrastructure.tool.AstParseTool;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.codepilot.core.infrastructure.tool.SearchPatternTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalReviewRunner {

    private static final Pattern HUNK_PATTERN = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final LlmClient llmClient;

    private final ObjectMapper objectMapper;

    private final TokenCounter tokenCounter;

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
        this.model = model;
        this.llmParams = llmParams == null ? Map.of() : Map.copyOf(llmParams);
        this.maxIterations = maxIterations;
    }

    public ReviewResult run(Path diffFile, Path repoRoot) {
        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        ParsedDiff parsedDiff = parseDiff(diffFile);

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
                parsedDiff.changedFiles().stream().map(DiffSummary.ChangedFile::path).toList(),
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
                new ContextPack(
                        Map.of("language", "java", "entrypoint", "cli"),
                        DiffSummary.of(parsedDiff.changedFiles()),
                        new ImpactSet(parsedDiff.impactedFiles(), Set.of(), List.of()),
                        parsedDiff.snippets(),
                        ProjectMemory.empty("cli-project"),
                        new ContextPack.TokenBudget(8000, 1000, tokenCounter.countText(parsedDiff.rawDiff()))
                )
        );
    }

    private ParsedDiff parseDiff(Path diffFile) {
        try {
            List<String> lines = Files.readAllLines(diffFile);
            List<DiffSummary.ChangedFile> changedFiles = new ArrayList<>();
            List<ContextPack.CodeSnippet> snippets = new ArrayList<>();
            Set<String> impactedFiles = new LinkedHashSet<>();

            String currentPath = null;
            DiffSummary.ChangeType currentType = DiffSummary.ChangeType.MODIFIED;
            int additions = 0;
            int deletions = 0;
            int hunkStartLine = -1;
            int hunkLength = 0;
            StringBuilder hunkContent = new StringBuilder();

            for (String line : lines) {
                if (line.startsWith("diff --git ")) {
                    if (currentPath != null) {
                        changedFiles.add(new DiffSummary.ChangedFile(currentPath, currentType, additions, deletions, List.of()));
                        flushSnippet(snippets, currentPath, hunkStartLine, hunkLength, hunkContent);
                    }
                    currentPath = extractPath(line);
                    currentType = DiffSummary.ChangeType.MODIFIED;
                    additions = 0;
                    deletions = 0;
                    hunkStartLine = -1;
                    hunkLength = 0;
                    hunkContent = new StringBuilder();
                    if (currentPath != null) {
                        impactedFiles.add(currentPath);
                    }
                    continue;
                }

                if (line.startsWith("new file mode")) {
                    currentType = DiffSummary.ChangeType.ADDED;
                    continue;
                }
                if (line.startsWith("deleted file mode")) {
                    currentType = DiffSummary.ChangeType.DELETED;
                    continue;
                }
                if (line.startsWith("@@ ")) {
                    flushSnippet(snippets, currentPath, hunkStartLine, hunkLength, hunkContent);
                    Matcher matcher = HUNK_PATTERN.matcher(line);
                    if (matcher.find()) {
                        hunkStartLine = Integer.parseInt(matcher.group(1));
                        hunkLength = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
                    } else {
                        hunkStartLine = 1;
                        hunkLength = 1;
                    }
                    hunkContent = new StringBuilder().append(line).append(System.lineSeparator());
                    continue;
                }
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    additions++;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    deletions++;
                }
                if (hunkStartLine > 0) {
                    hunkContent.append(line).append(System.lineSeparator());
                }
            }

            if (currentPath != null) {
                changedFiles.add(new DiffSummary.ChangedFile(currentPath, currentType, additions, deletions, List.of()));
                flushSnippet(snippets, currentPath, hunkStartLine, hunkLength, hunkContent);
            }

            return new ParsedDiff(String.join(System.lineSeparator(), lines), changedFiles, impactedFiles, snippets);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read diff file " + diffFile, error);
        }
    }

    private void flushSnippet(
            List<ContextPack.CodeSnippet> snippets,
            String currentPath,
            int hunkStartLine,
            int hunkLength,
            StringBuilder hunkContent
    ) {
        if (currentPath == null || hunkStartLine <= 0 || hunkContent == null || hunkContent.isEmpty()) {
            return;
        }
        int endLine = Math.max(hunkStartLine, hunkStartLine + Math.max(hunkLength - 1, 0));
        snippets.add(new ContextPack.CodeSnippet(
                currentPath,
                hunkStartLine,
                endLine,
                hunkContent.toString().trim(),
                "Unified diff hunk"
        ));
    }

    private String extractPath(String diffHeader) {
        String[] parts = diffHeader.split(" ");
        if (parts.length < 4) {
            return null;
        }
        String candidate = parts[3];
        return candidate.startsWith("b/") ? candidate.substring(2) : candidate;
    }

    private record ParsedDiff(
            String rawDiff,
            List<DiffSummary.ChangedFile> changedFiles,
            Set<String> impactedFiles,
            List<ContextPack.CodeSnippet> snippets
    ) {
    }
}
