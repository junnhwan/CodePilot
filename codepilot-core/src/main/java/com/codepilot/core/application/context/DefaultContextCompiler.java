package com.codepilot.core.application.context;

import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.context.AstParser;
import com.codepilot.core.domain.context.CompilationStrategy;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.memory.ProjectMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DefaultContextCompiler implements ContextCompiler {

    private final DiffAnalyzer diffAnalyzer;

    private final AstParser astParser;

    private final ImpactCalculator impactCalculator;

    private final TokenCounter tokenCounter;

    private final CompilationStrategy compilationStrategy;

    public DefaultContextCompiler(
            DiffAnalyzer diffAnalyzer,
            AstParser astParser,
            ImpactCalculator impactCalculator,
            TokenCounter tokenCounter,
            CompilationStrategy compilationStrategy
    ) {
        this.diffAnalyzer = diffAnalyzer;
        this.astParser = astParser;
        this.impactCalculator = impactCalculator;
        this.tokenCounter = tokenCounter;
        this.compilationStrategy = compilationStrategy;
    }

    @Override
    public ContextPack compile(
            Path repoRoot,
            String rawDiff,
            ProjectMemory projectMemory,
            Map<String, String> structuredFacts
    ) {
        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        DiffAnalyzer.DiffAnalysis diffAnalysis = diffAnalyzer.analyze(rawDiff);

        LinkedHashMap<String, AstParser.ParsedSourceFile> parsedChangedFiles = new LinkedHashMap<>();
        for (DiffAnalyzer.FileDelta fileDelta : diffAnalysis.fileDeltas()) {
            if (!isJavaSource(fileDelta.path()) || fileDelta.changeType() == com.codepilot.core.domain.context.DiffSummary.ChangeType.DELETED) {
                continue;
            }
            Path sourceFile = normalizedRepoRoot.resolve(fileDelta.path()).normalize();
            if (!sourceFile.startsWith(normalizedRepoRoot) || !Files.exists(sourceFile)) {
                continue;
            }
            parsedChangedFiles.put(fileDelta.path(), astParser.parse(normalizedRepoRoot, fileDelta.path()));
        }

        ImpactCalculator.ImpactAnalysis impactAnalysis = impactCalculator.calculate(
                normalizedRepoRoot,
                diffAnalysis,
                parsedChangedFiles,
                astParser,
                compilationStrategy
        );

        List<SnippetCandidate> candidates = new ArrayList<>();
        addChangedFileSnippets(normalizedRepoRoot, diffAnalysis, candidates);
        addDependencySnippets(impactAnalysis.dependencyFiles(), candidates);

        List<ContextPack.CodeSnippet> snippets = selectSnippets(candidates);
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("profileId", compilationStrategy.profileId());
        facts.put("language", compilationStrategy.language());
        facts.put("framework", compilationStrategy.framework());
        facts.put("buildTool", compilationStrategy.buildTool());
        if (structuredFacts != null) {
            facts.putAll(structuredFacts);
        }

        int usedTokens = tokenCounter.countText(diffAnalysis.rawDiff());
        for (ContextPack.CodeSnippet snippet : snippets) {
            usedTokens += tokenCounter.countText(snippet.content());
        }
        usedTokens = Math.min(usedTokens, compilationStrategy.tokenBudget().total());

        return new ContextPack(
                facts,
                impactAnalysis.diffSummary(),
                impactAnalysis.impactSet(),
                snippets,
                projectMemory,
                new ContextPack.TokenBudget(
                        compilationStrategy.tokenBudget().total(),
                        compilationStrategy.tokenBudget().reserve(),
                        usedTokens
                )
        );
    }

    private void addChangedFileSnippets(
            Path repoRoot,
            DiffAnalyzer.DiffAnalysis diffAnalysis,
            List<SnippetCandidate> candidates
    ) {
        for (DiffAnalyzer.FileDelta fileDelta : diffAnalysis.fileDeltas()) {
            if (!isJavaSource(fileDelta.path()) || fileDelta.changeType() == com.codepilot.core.domain.context.DiffSummary.ChangeType.DELETED) {
                continue;
            }
            Path sourceFile = repoRoot.resolve(fileDelta.path()).normalize();
            if (!sourceFile.startsWith(repoRoot) || !Files.exists(sourceFile)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(sourceFile);
                ContextPack.CodeSnippet snippet = toChangedFileSnippet(fileDelta, lines);
                candidates.add(new SnippetCandidate("changed_files", snippet));
            } catch (IOException error) {
                throw new IllegalStateException("Failed to read changed file " + fileDelta.path(), error);
            }
        }
    }

    private ContextPack.CodeSnippet toChangedFileSnippet(DiffAnalyzer.FileDelta fileDelta, List<String> lines) {
        String fullContent = String.join(System.lineSeparator(), lines);
        int fullContentTokens = tokenCounter.countText(fullContent);
        if (fullContentTokens <= compilationStrategy.tokenBudget().codeSnippets()) {
            return new ContextPack.CodeSnippet(
                    fileDelta.path(),
                    1,
                    Math.max(lines.size(), 1),
                    fullContent,
                    "Changed file content (full file)"
            );
        }

        int minLine = fileDelta.hunks().stream()
                .mapToInt(DiffAnalyzer.ChangedHunk::startLine)
                .filter(line -> line > 0)
                .min()
                .orElse(1);
        int maxLine = fileDelta.hunks().stream()
                .mapToInt(DiffAnalyzer.ChangedHunk::endLine)
                .filter(line -> line > 0)
                .max()
                .orElse(Math.max(lines.size(), 1));
        int startLine = Math.max(1, minLine - 3);
        int endLine = Math.min(lines.size(), maxLine + 3);
        List<String> window = lines.subList(startLine - 1, Math.max(endLine, startLine - 1));
        return new ContextPack.CodeSnippet(
                fileDelta.path(),
                startLine,
                Math.max(endLine, startLine),
                String.join(System.lineSeparator(), window),
                "Changed file content (focused around hunks)"
        );
    }

    private void addDependencySnippets(
            Map<String, AstParser.ParsedSourceFile> dependencyFiles,
            List<SnippetCandidate> candidates
    ) {
        for (AstParser.ParsedSourceFile dependencyFile : dependencyFiles.values()) {
            String summary = formatDependencySummary(dependencyFile, compilationStrategy.astModeFor("direct_callees"));
            int startLine = dependencyFile.types().isEmpty() ? 1 : dependencyFile.types().getFirst().startLine();
            int endLine = dependencyFile.types().isEmpty() ? 1 : dependencyFile.types().getLast().endLine();
            candidates.add(new SnippetCandidate(
                    "direct_callees",
                    new ContextPack.CodeSnippet(
                            dependencyFile.filePath(),
                            startLine,
                            endLine,
                            summary,
                            "Direct dependency symbols (%s)".formatted(dependencyFile.parseMode())
                    )
            ));
        }
    }

    private String formatDependencySummary(AstParser.ParsedSourceFile sourceFile, CompilationStrategy.AstMode astMode) {
        StringBuilder builder = new StringBuilder();
        builder.append("parse_mode=").append(sourceFile.parseMode()).append(System.lineSeparator());
        if (!sourceFile.packageName().isBlank()) {
            builder.append("package ").append(sourceFile.packageName()).append(System.lineSeparator());
        }
        if (!sourceFile.imports().isEmpty()) {
            builder.append("imports:").append(System.lineSeparator());
            sourceFile.imports().forEach(importName -> builder.append("- ").append(importName).append(System.lineSeparator()));
        }

        for (AstParser.TypeSymbol type : sourceFile.types()) {
            builder.append("type ").append(type.simpleName()).append(System.lineSeparator());
            if (astMode == CompilationStrategy.AstMode.FULL || astMode == CompilationStrategy.AstMode.METHOD_SIG) {
                type.methods().forEach(method -> builder.append("- ")
                        .append(method.symbolName())
                        .append(" :: ")
                        .append(method.signature())
                        .append(System.lineSeparator()));
            } else {
                type.methods().forEach(method -> builder.append("- ").append(method.symbolName()).append(System.lineSeparator()));
            }
        }
        return builder.toString().trim();
    }

    private List<ContextPack.CodeSnippet> selectSnippets(List<SnippetCandidate> candidates) {
        Map<String, List<SnippetCandidate>> byBucket = new LinkedHashMap<>();
        for (SnippetCandidate candidate : candidates) {
            byBucket.computeIfAbsent(candidate.bucket(), ignored -> new ArrayList<>()).add(candidate);
        }

        int remainingBudget = compilationStrategy.tokenBudget().codeSnippets();
        List<ContextPack.CodeSnippet> selected = new ArrayList<>();
        Set<String> selectedKeys = new LinkedHashSet<>();

        for (String bucket : compilationStrategy.filePriority()) {
            List<SnippetCandidate> bucketCandidates = byBucket.getOrDefault(bucket, List.of());
            for (SnippetCandidate candidate : bucketCandidates) {
                int estimatedTokens = tokenCounter.countText(candidate.snippet().content());
                boolean mustKeep = "changed_files".equals(bucket);
                if (!mustKeep && estimatedTokens > remainingBudget) {
                    continue;
                }
                String selectedKey = candidate.snippet().filePath() + "#" + candidate.bucket();
                if (selectedKeys.add(selectedKey)) {
                    selected.add(candidate.snippet());
                    remainingBudget = Math.max(0, remainingBudget - estimatedTokens);
                }
            }
        }
        return List.copyOf(selected);
    }

    private boolean isJavaSource(String filePath) {
        return filePath != null && filePath.endsWith(".java");
    }

    private record SnippetCandidate(
            String bucket,
            ContextPack.CodeSnippet snippet
    ) {
    }
}
