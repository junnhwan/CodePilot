package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MemorySearchTool implements Tool {

    private final ProjectMemoryRepository projectMemoryRepository;

    private final String defaultProjectId;

    public MemorySearchTool(ProjectMemoryRepository projectMemoryRepository, String defaultProjectId) {
        this.projectMemoryRepository = projectMemoryRepository;
        this.defaultProjectId = defaultProjectId == null ? "" : defaultProjectId.trim();
    }

    @Override
    public String name() {
        return "memory_search";
    }

    @Override
    public String description() {
        return "Search minimal project memory entries by keyword across review patterns and team conventions.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "project_id", Map.of("type", "string"),
                        "query", Map.of("type", "string"),
                        "max_results", Map.of("type", "integer")
                ),
                "required", List.of("query")
        );
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public boolean exclusive() {
        return false;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String query = String.valueOf(call.arguments().getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            return ToolResult.failure(call.callId(), "query must not be blank", Map.of());
        }

        String projectId = String.valueOf(call.arguments().getOrDefault("project_id", defaultProjectId)).trim();
        if (projectId.isBlank()) {
            return ToolResult.failure(call.callId(), "project_id must not be blank", Map.of());
        }

        int maxResults = RepositoryToolSupport.positiveInt(call.arguments().get("max_results"), 10);
        ProjectMemory projectMemory = projectMemoryRepository.findByProjectId(projectId)
                .orElse(ProjectMemory.empty(projectId));

        List<SearchHit> hits = new ArrayList<>();
        for (ReviewPattern reviewPattern : projectMemory.reviewPatterns()) {
            double score = score(query, reviewPattern.title(), reviewPattern.description(), reviewPattern.codeExample())
                    + reviewPattern.frequency() * 0.01d;
            if (score <= 0.0d) {
                continue;
            }
            hits.add(new SearchHit(
                    "review_pattern",
                    reviewPattern.patternId(),
                    score,
                    "[%s] %s%n%s".formatted(reviewPattern.patternType(), reviewPattern.title(), reviewPattern.description())
            ));
        }
        for (TeamConvention teamConvention : projectMemory.teamConventions()) {
            double score = score(query, teamConvention.rule(), teamConvention.exampleGood(), teamConvention.exampleBad())
                    + teamConvention.confidence() * 0.01d;
            if (score <= 0.0d) {
                continue;
            }
            hits.add(new SearchHit(
                    "team_convention",
                    teamConvention.conventionId(),
                    score,
                    "[%s] %s".formatted(teamConvention.category(), teamConvention.rule())
            ));
        }

        List<SearchHit> rankedHits = hits.stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed()
                        .thenComparing(SearchHit::entryId))
                .limit(maxResults)
                .toList();

        String output = rankedHits.stream()
                .map(SearchHit::summary)
                .reduce((left, right) -> left + System.lineSeparator() + System.lineSeparator() + right)
                .orElse("");

        return ToolResult.success(call.callId(), output, Map.of(
                "projectId", projectId,
                "matchCount", rankedHits.size()
        ));
    }

    private double score(String query, String... fields) {
        List<String> tokens = List.of(query.toLowerCase(Locale.ROOT).split("\\s+"));
        double score = 0.0d;
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String normalizedField = field.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (token.isBlank()) {
                    continue;
                }
                if (normalizedField.contains(token)) {
                    score += 1.0d;
                }
            }
        }
        return score;
    }

    private record SearchHit(
            String entryType,
            String entryId,
            double score,
            String summary
    ) {
    }
}
