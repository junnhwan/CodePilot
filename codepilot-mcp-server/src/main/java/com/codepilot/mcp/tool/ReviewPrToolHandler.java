package com.codepilot.mcp.tool;

import com.codepilot.mcp.review.GitHubPullRequestClient;
import com.codepilot.mcp.review.McpReviewService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;
import java.util.function.BiFunction;

public final class ReviewPrToolHandler implements BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final McpReviewService reviewService;

    private final GitHubPullRequestClient gitHubPullRequestClient;

    public ReviewPrToolHandler(McpReviewService reviewService, GitHubPullRequestClient gitHubPullRequestClient) {
        this.reviewService = reviewService;
        this.gitHubPullRequestClient = gitHubPullRequestClient;
    }

    public McpServerFeatures.SyncToolSpecification specification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("review_pr")
                        .title("Review Pull Request")
                        .description("Fetch a GitHub pull request, materialize its changed files, and review it with CodePilot's planning, context compilation, multi-agent review, and merge pipeline.")
                        .inputSchema(JSON_MAPPER, """
                                {
                                  "type": "object",
                                  "properties": {
                                    "owner": { "type": "string" },
                                    "repository": { "type": "string" },
                                    "pr_number": { "type": "integer" },
                                    "project_id": { "type": "string" },
                                    "api_base_url": { "type": "string" },
                                    "api_token": { "type": "string" }
                                  },
                                  "required": ["owner", "repository", "pr_number"]
                                }
                                """)
                        .outputSchema(ReviewDiffToolHandler.reviewOutputSchema())
                        .annotations(new McpSchema.ToolAnnotations("Review Pull Request", Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE))
                        .build())
                .callHandler(this)
                .build();
    }

    @Override
    public McpSchema.CallToolResult apply(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            String owner = requiredText(request.arguments(), "owner");
            String repository = requiredText(request.arguments(), "repository");
            int prNumber = positiveInt(request.arguments().get("pr_number"), "pr_number");
            String projectId = optionalText(request.arguments(), "project_id");
            if (projectId == null) {
                projectId = owner + "/" + repository;
            }
            String apiBaseUrl = optionalText(request.arguments(), "api_base_url");
            if (apiBaseUrl == null) {
                apiBaseUrl = "https://api.github.com";
            }
            String apiToken = optionalText(request.arguments(), "api_token");

            McpReviewService.ReviewResponse response = reviewService.reviewPr(
                    new McpReviewService.ReviewPrRequest(
                            owner,
                            repository,
                            prNumber,
                            projectId,
                            apiBaseUrl,
                            apiToken == null ? "" : apiToken
                    ),
                    gitHubPullRequestClient
            );
            return McpSchema.CallToolResult.builder()
                    .addTextContent(response.textSummary("review_pr"))
                    .structuredContent(response.structuredContent())
                    .build();
        } catch (IllegalArgumentException validationError) {
            return errorResult("review_pr validation failed: " + validationError.getMessage());
        } catch (RuntimeException runtimeError) {
            return errorResult("review_pr failed: " + runtimeError.getMessage());
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

    private static int positiveInt(Object rawValue, String fieldName) {
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
                // fall through to validation error
            }
        }
        throw new IllegalArgumentException(fieldName + " must be a positive integer");
    }
}
