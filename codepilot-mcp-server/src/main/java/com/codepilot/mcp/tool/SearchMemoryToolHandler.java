package com.codepilot.mcp.tool;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

public final class SearchMemoryToolHandler implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final ProjectMemoryRepository projectMemoryRepository;

    public SearchMemoryToolHandler(ProjectMemoryRepository projectMemoryRepository) {
        this.projectMemoryRepository = projectMemoryRepository;
    }

    public McpServerFeatures.SyncToolSpecification specification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("search_memory")
                        .title("Search Project Memory")
                        .description("Search minimal project memory entries by keyword across review patterns and team conventions.")
                        .inputSchema(JSON_MAPPER, """
                                {
                                  "type": "object",
                                  "properties": {
                                    "project_id": { "type": "string" },
                                    "query": { "type": "string" },
                                    "max_results": { "type": "integer" }
                                  },
                                  "required": ["project_id", "query"]
                                }
                                """)
                        .outputSchema(memoryOutputSchema())
                        .annotations(new McpSchema.ToolAnnotations("Search Project Memory", Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                        .build())
                .callHandler(this)
                .build();
    }

    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            String projectId = requiredText(request.arguments(), "project_id");
            String query = requiredText(request.arguments(), "query");
            int maxResults = positiveInt(request.arguments().get("max_results"), 10);
            ProjectMemory projectMemory = projectMemoryRepository.findByProjectId(projectId)
                    .orElseGet(() -> ProjectMemory.empty(projectId));

            List<MemoryMatch> matches = search(projectMemory, query, maxResults);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(textSummary(matches))
                    .structuredContent(structuredContent(projectId, query, matches))
                    .build();
        } catch (IllegalArgumentException validationError) {
            return errorResult("search_memory validation failed: " + validationError.getMessage());
        } catch (RuntimeException runtimeError) {
            return errorResult("search_memory failed: " + runtimeError.getMessage());
        }
    }

    private List<MemoryMatch> search(ProjectMemory projectMemory, String query, int maxResults) {
        List<String> tokens = tokenize(query);
        List<MemoryMatch> matches = new ArrayList<>();
        for (ReviewPattern reviewPattern : projectMemory.reviewPatterns()) {
            double score = score(tokens, reviewPattern.title(), reviewPattern.description(), reviewPattern.codeExample())
                    + Math.min(reviewPattern.frequency(), 10) * 0.1d;
            if (score <= 0.0d) {
                continue;
            }
            matches.add(new MemoryMatch(
                    "review_pattern",
                    reviewPattern.patternId(),
                    score,
                    reviewPattern.patternType().name(),
                    reviewPattern.title(),
                    reviewPattern.description(),
                    reviewPattern.codeExample(),
                    reviewPattern.frequency(),
                    null
            ));
        }
        for (TeamConvention teamConvention : projectMemory.teamConventions()) {
            double score = score(tokens, teamConvention.rule(), teamConvention.exampleGood(), teamConvention.exampleBad())
                    + teamConvention.confidence() * 0.1d;
            if (score <= 0.0d) {
                continue;
            }
            matches.add(new MemoryMatch(
                    "team_convention",
                    teamConvention.conventionId(),
                    score,
                    teamConvention.category().name(),
                    teamConvention.rule(),
                    teamConvention.exampleGood(),
                    teamConvention.exampleBad(),
                    null,
                    teamConvention.confidence()
            ));
        }
        return matches.stream()
                .sorted(Comparator.comparingDouble(MemoryMatch::score).reversed()
                        .thenComparing(MemoryMatch::entryId))
                .limit(maxResults)
                .toList();
    }

    private double score(List<String> tokens, String... fields) {
        double score = 0.0d;
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String normalized = field.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (normalized.contains(token)) {
                    score += 1.0d;
                }
            }
        }
        return score;
    }

    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Set.copyOf(List.of(query.toLowerCase(Locale.ROOT).split("\\s+"))).stream()
                .filter(token -> !token.isBlank())
                .toList();
    }

    private String textSummary(List<MemoryMatch> matches) {
        if (matches.isEmpty()) {
            return "search_memory completed: match_count=0";
        }
        StringBuilder builder = new StringBuilder("search_memory completed: match_count=")
                .append(matches.size());
        for (MemoryMatch match : matches) {
            builder.append(System.lineSeparator())
                    .append("- [")
                    .append(match.entryType())
                    .append("] ")
                    .append(match.title());
        }
        return builder.toString();
    }

    private Map<String, Object> structuredContent(String projectId, String query, List<MemoryMatch> matches) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("project_id", projectId);
        payload.put("query", query);
        payload.put("match_count", matches.size());
        payload.put("matches", matches.stream().map(SearchMemoryToolHandler::matchPayload).toList());
        return Map.copyOf(payload);
    }

    private static Map<String, Object> matchPayload(MemoryMatch match) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("entry_type", match.entryType());
        payload.put("entry_id", match.entryId());
        payload.put("category", match.category());
        payload.put("title", match.title());
        payload.put("description", match.description());
        payload.put("example", match.example());
        payload.put("frequency", match.frequency());
        payload.put("confidence", match.confidence());
        return payload;
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .isError(Boolean.TRUE)
                .addTextContent(message)
                .build();
    }

    private static String requiredText(Map<String, Object> arguments, String fieldName) {
        Object rawValue = arguments == null ? null : arguments.get(fieldName);
        if (rawValue == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String value = String.valueOf(rawValue).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static int positiveInt(Object rawValue, int defaultValue) {
        if (rawValue instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (rawValue instanceof String text) {
            try {
                int parsed = Integer.parseInt(text.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static Map<String, Object> memoryOutputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "project_id", Map.of("type", "string"),
                        "query", Map.of("type", "string"),
                        "match_count", Map.of("type", "integer"),
                        "matches", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "entry_type", Map.of("type", "string"),
                                                "entry_id", Map.of("type", "string"),
                                                "category", Map.of("type", "string"),
                                                "title", Map.of("type", "string"),
                                                "description", Map.of("type", "string"),
                                                "example", Map.of("type", "string"),
                                                "frequency", Map.of("type", java.util.List.of("integer", "null")),
                                                "confidence", Map.of("type", java.util.List.of("number", "null"))
                                        ),
                                        "required", java.util.List.of(
                                                "entry_type",
                                                "entry_id",
                                                "category",
                                                "title",
                                                "description",
                                                "example",
                                                "frequency",
                                                "confidence"
                                        )
                                )
                        )
                ),
                "required", java.util.List.of("project_id", "query", "match_count", "matches")
        );
    }

    private record MemoryMatch(
            String entryType,
            String entryId,
            double score,
            String category,
            String title,
            String description,
            String example,
            Integer frequency,
            Double confidence
    ) {
    }
}
