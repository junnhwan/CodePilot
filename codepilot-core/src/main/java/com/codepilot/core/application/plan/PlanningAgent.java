package com.codepilot.core.application.plan;

import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.plan.TaskGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PlanningAgent {

    private final DiffAnalyzer diffAnalyzer;

    public PlanningAgent(DiffAnalyzer diffAnalyzer) {
        this.diffAnalyzer = diffAnalyzer;
    }

    public ReviewPlan plan(String sessionId, String rawDiff) {
        return plan(sessionId, diffAnalyzer.analyze(rawDiff));
    }

    public ReviewPlan plan(String sessionId, DiffAnalyzer.DiffAnalysis diffAnalysis) {
        DiffSummary diffSummary = toDiffSummary(diffAnalysis);
        List<FileSignals> fileSignals = diffAnalysis.fileDeltas().stream()
                .map(this::classify)
                .toList();

        ReviewPlan.ReviewStrategy strategy = selectStrategy(diffSummary, fileSignals);
        List<String> reviewableFiles = reviewableFiles(fileSignals);

        ReviewTask securityTask = ReviewTask.pending(
                "task-security",
                ReviewTask.TaskType.SECURITY,
                securityPriority(strategy, fileSignals),
                targetFiles(fileSignals, reviewableFiles, FileSignals::securitySensitive),
                securityFocusHints(fileSignals, diffSummary),
                List.of()
        );
        ReviewTask perfTask = ReviewTask.pending(
                "task-perf",
                ReviewTask.TaskType.PERF,
                perfPriority(strategy, fileSignals, diffSummary),
                targetFiles(fileSignals, reviewableFiles, FileSignals::performanceSensitive),
                perfFocusHints(fileSignals, diffSummary),
                List.of()
        );
        ReviewTask styleTask = ReviewTask.pending(
                "task-style",
                ReviewTask.TaskType.STYLE,
                stylePriority(diffSummary),
                reviewableFiles,
                styleFocusHints(diffSummary),
                styleDependencies(strategy)
        );
        ReviewTask maintainTask = ReviewTask.pending(
                "task-maintain",
                ReviewTask.TaskType.MAINTAIN,
                maintainPriority(diffSummary),
                reviewableFiles,
                maintainFocusHints(diffSummary),
                maintainDependencies(strategy)
        );

        return new ReviewPlan(
                "plan-" + sessionId,
                sessionId,
                diffSummary,
                TaskGraph.of(List.of(securityTask, perfTask, styleTask, maintainTask)),
                strategy
        );
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

    private FileSignals classify(DiffAnalyzer.FileDelta fileDelta) {
        String path = fileDelta.path() == null ? "" : fileDelta.path();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String hunkText = fileDelta.hunks().stream()
                .map(DiffAnalyzer.ChangedHunk::content)
                .reduce("", (left, right) -> left + "\n" + right)
                .toLowerCase(Locale.ROOT);

        boolean documentation = lowerPath.endsWith(".md") || lowerPath.endsWith(".txt");
        boolean securitySensitive = containsAny(lowerPath,
                "auth", "security", "token", "password", "permission", "role", "oauth", "login", "controller", "filter")
                || containsAny(hunkText, "token", "password", "authorize", "permission", "jwt", "credential", "login");
        boolean performanceSensitive = containsAny(lowerPath,
                "repository", "dao", "service", "cache", "client", "batch", "thread", "executor")
                || containsAny(hunkText, "jdbc", "query", "select ", "for (", ".stream()", "parallel", "cache", "redis");

        return new FileSignals(
                path,
                documentation,
                securitySensitive,
                performanceSensitive,
                fileDelta.additions() + fileDelta.deletions()
        );
    }

    private ReviewPlan.ReviewStrategy selectStrategy(DiffSummary diffSummary, List<FileSignals> fileSignals) {
        if (fileSignals.stream().anyMatch(FileSignals::securitySensitive)) {
            return ReviewPlan.ReviewStrategy.SECURITY_FIRST;
        }
        if (fileSignals.stream().anyMatch(FileSignals::performanceSensitive)) {
            return ReviewPlan.ReviewStrategy.PERFORMANCE_FIRST;
        }
        if (diffSummary.changedFileCount() >= 3 || diffSummary.totalChangedLines() >= 40) {
            return ReviewPlan.ReviewStrategy.COMPREHENSIVE;
        }
        return ReviewPlan.ReviewStrategy.QUICK_SCAN;
    }

    private ReviewTask.Priority securityPriority(ReviewPlan.ReviewStrategy strategy, List<FileSignals> fileSignals) {
        if (strategy == ReviewPlan.ReviewStrategy.SECURITY_FIRST || fileSignals.stream().anyMatch(FileSignals::securitySensitive)) {
            return ReviewTask.Priority.HIGH;
        }
        return ReviewTask.Priority.MEDIUM;
    }

    private ReviewTask.Priority perfPriority(
            ReviewPlan.ReviewStrategy strategy,
            List<FileSignals> fileSignals,
            DiffSummary diffSummary
    ) {
        if (strategy == ReviewPlan.ReviewStrategy.PERFORMANCE_FIRST || fileSignals.stream().anyMatch(FileSignals::performanceSensitive)) {
            return ReviewTask.Priority.HIGH;
        }
        if (diffSummary.totalChangedLines() >= 30 || diffSummary.changedFileCount() >= 3) {
            return ReviewTask.Priority.MEDIUM;
        }
        return ReviewTask.Priority.LOW;
    }

    private ReviewTask.Priority stylePriority(DiffSummary diffSummary) {
        return diffSummary.totalChangedLines() >= 20 ? ReviewTask.Priority.MEDIUM : ReviewTask.Priority.LOW;
    }

    private ReviewTask.Priority maintainPriority(DiffSummary diffSummary) {
        if (diffSummary.changedFileCount() >= 3 || diffSummary.totalChangedLines() >= 20) {
            return ReviewTask.Priority.HIGH;
        }
        return diffSummary.changedFileCount() >= 2 ? ReviewTask.Priority.MEDIUM : ReviewTask.Priority.LOW;
    }

    private List<String> reviewableFiles(List<FileSignals> fileSignals) {
        List<String> codeFiles = fileSignals.stream()
                .filter(fileSignalsEntry -> !fileSignalsEntry.documentation())
                .map(FileSignals::path)
                .distinct()
                .toList();
        if (!codeFiles.isEmpty()) {
            return codeFiles;
        }
        return fileSignals.stream()
                .map(FileSignals::path)
                .distinct()
                .toList();
    }

    private List<String> targetFiles(
            List<FileSignals> fileSignals,
            List<String> fallbackFiles,
            java.util.function.Predicate<FileSignals> matcher
    ) {
        List<String> prioritized = fileSignals.stream()
                .filter(matcher)
                .map(FileSignals::path)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return prioritized.isEmpty() ? fallbackFiles : prioritized;
    }

    private List<String> securityFocusHints(List<FileSignals> fileSignals, DiffSummary diffSummary) {
        List<String> hints = new ArrayList<>();
        if (fileSignals.stream().anyMatch(FileSignals::securitySensitive)) {
            hints.add("Inspect authentication, authorization, and secret-handling paths before lower-signal concerns.");
        }
        hints.add("Check whether untrusted input can reach query, command, or permission boundaries without validation.");
        if (diffSummary.changedFileCount() >= 3) {
            hints.add("Start with newly touched entry points and fail-fast guards introduced in this change set.");
        }
        return List.copyOf(hints);
    }

    private List<String> perfFocusHints(List<FileSignals> fileSignals, DiffSummary diffSummary) {
        List<String> hints = new ArrayList<>();
        hints.add("Inspect query patterns, loops, and remote calls introduced by the diff.");
        if (fileSignals.stream().anyMatch(FileSignals::performanceSensitive)) {
            hints.add("Check repository or service changes for repeated query execution, cache misses, or unnecessary allocations.");
        }
        if (diffSummary.totalChangedLines() >= 20) {
            hints.add("Prioritize hotspots where new work sits inside loops or fan-out code paths.");
        }
        return List.copyOf(hints);
    }

    private List<String> styleFocusHints(DiffSummary diffSummary) {
        List<String> hints = new ArrayList<>();
        hints.add("Check naming consistency, comment tone, and formatting around the touched files.");
        if (diffSummary.changedFileCount() >= 2) {
            hints.add("Prefer existing project terminology so new identifiers do not introduce parallel concepts.");
        }
        return List.copyOf(hints);
    }

    private List<String> maintainFocusHints(DiffSummary diffSummary) {
        List<String> hints = new ArrayList<>();
        hints.add("Review complexity growth, branching, and duplication introduced by the change.");
        if (diffSummary.changedFileCount() >= 3 || diffSummary.totalChangedLines() >= 20) {
            hints.add("Look for complexity spikes, responsibility spread, or glue code that weakens module boundaries.");
        }
        return List.copyOf(hints);
    }

    private List<String> styleDependencies(ReviewPlan.ReviewStrategy strategy) {
        return strategy == ReviewPlan.ReviewStrategy.QUICK_SCAN ? List.of() : List.of("task-security");
    }

    private List<String> maintainDependencies(ReviewPlan.ReviewStrategy strategy) {
        if (strategy == ReviewPlan.ReviewStrategy.QUICK_SCAN) {
            return List.of("task-style");
        }
        return List.of("task-security", "task-perf");
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private record FileSignals(
            String path,
            boolean documentation,
            boolean securitySensitive,
            boolean performanceSensitive,
            int changedLines
    ) {
    }
}
