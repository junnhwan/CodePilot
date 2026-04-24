package com.codepilot.eval;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record EvalScenario(
        String scenarioId,
        String name,
        String description,
        String projectId,
        Map<String, String> structuredFacts,
        List<RepositoryFile> repositoryFiles,
        List<String> diffLines,
        ProjectMemory projectMemory,
        List<GroundTruthFinding> groundTruth,
        StopPolicy stopPolicy
) {

    public EvalScenario {
        scenarioId = requireText(scenarioId, "scenarioId");
        name = requireText(name, "name");
        description = description == null ? "" : description.trim();
        projectId = requireText(projectId, "projectId");
        structuredFacts = structuredFacts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(structuredFacts));
        repositoryFiles = immutableList(repositoryFiles);
        if (repositoryFiles.isEmpty()) {
            throw new IllegalArgumentException("EvalScenario[%s] repositoryFiles must not be empty".formatted(scenarioId));
        }
        diffLines = immutableList(diffLines);
        if (diffLines.isEmpty()) {
            throw new IllegalArgumentException("EvalScenario[%s] diffLines must not be empty".formatted(scenarioId));
        }
        projectMemory = projectMemory == null ? ProjectMemory.empty(projectId) : projectMemory;
        if (!projectId.equals(projectMemory.projectId())) {
            throw new IllegalArgumentException("EvalScenario[%s] projectMemory projectId mismatch".formatted(scenarioId));
        }
        groundTruth = immutableList(groundTruth);
        stopPolicy = stopPolicy == null ? new StopPolicy(6, 5) : stopPolicy;
    }

    public String rawDiff() {
        return String.join("\n", diffLines);
    }

    public record RepositoryFile(
            String path,
            List<String> lines
    ) {

        public RepositoryFile {
            path = requireText(path, "path");
            lines = immutableList(lines);
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("RepositoryFile[%s] lines must not be empty".formatted(path));
            }
        }

        public String content() {
            return String.join(System.lineSeparator(), lines);
        }
    }

    public record GroundTruthFinding(
            String filePath,
            int startLine,
            int endLine,
            Severity severity,
            String category,
            String title
    ) {

        public GroundTruthFinding {
            filePath = requireText(filePath, "filePath");
            if (startLine <= 0 || endLine < startLine) {
                throw new IllegalArgumentException("GroundTruthFinding[%s] has invalid line range %d-%d"
                        .formatted(filePath, startLine, endLine));
            }
            if (severity == null) {
                throw new IllegalArgumentException("GroundTruthFinding[%s] severity must not be null"
                        .formatted(filePath));
            }
            category = requireText(category, "category");
            title = requireText(title, "title");
        }

        public GroundTruthFinding(
                Finding.CodeLocation location,
                Severity severity,
                String category,
                String title
        ) {
            this(
                    location == null ? null : location.filePath(),
                    location == null ? 0 : location.startLine(),
                    location == null ? 0 : location.endLine(),
                    severity,
                    category,
                    title
            );
        }

        public Finding.CodeLocation location() {
            return new Finding.CodeLocation(filePath, startLine, endLine);
        }

        public boolean matches(Finding finding) {
            if (finding == null) {
                return false;
            }
            return normalize(filePath).equals(normalize(finding.location().filePath()))
                    && finding.location().startLine() <= startLine
                    && finding.location().endLine() >= endLine
                    && severity == finding.severity()
                    && normalize(category).equals(normalize(finding.category()))
                    && normalize(title).equals(normalize(finding.title()));
        }

        private static String normalize(String text) {
            return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        }
    }

    public record StopPolicy(
            int maxIterations,
            int maxTimeMinutes
    ) {

        public StopPolicy {
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("StopPolicy maxIterations must be positive");
            }
            if (maxTimeMinutes <= 0) {
                throw new IllegalArgumentException("StopPolicy maxTimeMinutes must be positive");
            }
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
