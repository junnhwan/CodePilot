package com.codepilot.mcp.tool;

import com.codepilot.mcp.review.McpReviewService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

public final class ReviewDiffToolHandler implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final McpReviewService reviewService;

    public ReviewDiffToolHandler(McpReviewService reviewService) {
        this.reviewService = reviewService;
    }

    public McpServerFeatures.SyncToolSpecification specification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("review_diff")
                        .title("Review Diff")
                        .description("Review a unified diff against a local repository by reusing CodePilot's planning, context compilation, multi-agent review, and merge pipeline.")
                        .inputSchema(JSON_MAPPER, """
                                {
                                  "type": "object",
                                  "properties": {
                                    "repo_root": { "type": "string" },
                                    "raw_diff": { "type": "string" },
                                    "project_id": { "type": "string" },
                                    "structured_facts": {
                                      "type": "object",
                                      "additionalProperties": { "type": "string" }
                                    }
                                  },
                                  "required": ["repo_root", "raw_diff"]
                                }
                                """)
                        .outputSchema(reviewOutputSchema())
                        .annotations(new McpSchema.ToolAnnotations("Review Diff", Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE))
                        .build())
                .callHandler(this)
                .build();
    }

    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            Path repoRoot = Path.of(requiredText(request.arguments(), "repo_root"));
            String rawDiff = requiredText(request.arguments(), "raw_diff");
            String projectId = optionalText(request.arguments(), "project_id");
            if (projectId == null) {
                projectId = repoRoot.toAbsolutePath().normalize().getFileName() == null
                        ? "codepilot-project"
                        : repoRoot.toAbsolutePath().normalize().getFileName().toString();
            }

            McpReviewService.ReviewResponse response = reviewService.reviewDiff(new McpReviewService.ReviewDiffRequest(
                    repoRoot,
                    rawDiff,
                    projectId,
                    stringMap(request.arguments().get("structured_facts"))
            ));
            return McpSchema.CallToolResult.builder()
                    .addTextContent(response.textSummary("review_diff"))
                    .structuredContent(response.structuredContent())
                    .build();
        } catch (IllegalArgumentException validationError) {
            return errorResult("review_diff validation failed: " + validationError.getMessage());
        } catch (RuntimeException runtimeError) {
            return errorResult("review_diff failed: " + runtimeError.getMessage());
        }
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .isError(Boolean.TRUE)
                .addTextContent(message)
                .build();
    }

    private static String requiredText(Map<String, Object> arguments, String fieldName) {
        String value = optionalText(arguments, fieldName);
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String optionalText(Map<String, Object> arguments, String fieldName) {
        Object rawValue = arguments == null ? null : arguments.get(fieldName);
        if (rawValue == null) {
            return null;
        }
        String value = String.valueOf(rawValue).trim();
        return value.isBlank() ? null : value;
    }

    private static Map<String, String> stringMap(Object rawValue) {
        if (!(rawValue instanceof Map<?, ?> mapValue)) {
            return Map.of();
        }
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            facts.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return Map.copyOf(facts);
    }

    static Map<String, Object> reviewOutputSchema() {
        return Map.ofEntries(
                Map.entry("type", "object"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("session_id", Map.of("type", "string")),
                        Map.entry("project_id", Map.of("type", "string")),
                        Map.entry("strategy", Map.of("type", "string")),
                        Map.entry("task_count", Map.of("type", "integer")),
                        Map.entry("finding_count", Map.of("type", "integer")),
                        Map.entry("partial", Map.of("type", "boolean")),
                        Map.entry("findings", Map.ofEntries(
                                Map.entry("type", "array"),
                                Map.entry("items", Map.ofEntries(
                                        Map.entry("type", "object"),
                                        Map.entry("properties", Map.ofEntries(
                                                Map.entry("finding_id", Map.of("type", "string")),
                                                Map.entry("task_id", Map.of("type", "string")),
                                                Map.entry("category", Map.of("type", "string")),
                                                Map.entry("severity", Map.of("type", "string")),
                                                Map.entry("confidence", Map.of("type", "number")),
                                                Map.entry("status", Map.of("type", "string")),
                                                Map.entry("file", Map.of("type", "string")),
                                                Map.entry("start_line", Map.of("type", "integer")),
                                                Map.entry("end_line", Map.of("type", "integer")),
                                                Map.entry("title", Map.of("type", "string")),
                                                Map.entry("description", Map.of("type", "string")),
                                                Map.entry("suggestion", Map.of("type", "string")),
                                                Map.entry("evidence", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "string")
                                                ))
                                        )),
                                        Map.entry("required", java.util.List.of(
                                                "finding_id",
                                                "task_id",
                                                "category",
                                                "severity",
                                                "confidence",
                                                "status",
                                                "file",
                                                "start_line",
                                                "end_line",
                                                "title",
                                                "description",
                                                "suggestion",
                                                "evidence"
                                        ))
                                ))
                        ))
                )),
                Map.entry("required", java.util.List.of(
                        "session_id",
                        "project_id",
                        "strategy",
                        "task_count",
                        "finding_count",
                        "partial",
                        "findings"
                ))
        );
    }
}
