package com.codepilot.core.application.review;

import com.codepilot.core.application.tool.ToolExecutor;
import com.codepilot.core.application.tool.ToolRegistry;
import com.codepilot.core.domain.agent.AgentDefinition;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ReviewEngine {

    private final LlmClient llmClient;

    private final ToolRegistry toolRegistry;

    private final ToolExecutor toolExecutor;

    private final ToolCallParser toolCallParser;

    private final TokenCounter tokenCounter;

    private final String model;

    private final Map<String, Object> llmParams;

    private final int maxIterations;

    public ReviewEngine(
            LlmClient llmClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ToolCallParser toolCallParser,
            TokenCounter tokenCounter,
            String model,
            Map<String, Object> llmParams,
            int maxIterations
    ) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.toolCallParser = toolCallParser;
        this.tokenCounter = tokenCounter;
        this.model = model;
        this.llmParams = llmParams == null ? Map.of() : Map.copyOf(llmParams);
        this.maxIterations = maxIterations;
    }

    public ReviewResult execute(
            String sessionId,
            AgentDefinition agentDefinition,
            ReviewTask reviewTask,
            ContextPack contextPack
    ) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(ReviewPromptTemplates.systemMessage(agentDefinition, reviewTask, contextPack, toolRegistry.toolDefinitions()));
        messages.add(ReviewPromptTemplates.userMessage(reviewTask, contextPack));

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            requireWithinBudget(messages, contextPack);
            var response = llmClient.chat(new LlmRequest(model, List.copyOf(messages), toolRegistry.toolDefinitions(), llmParams));
            List<ToolCall> toolCalls = toolCallParser.parse(response);

            if (!toolCalls.isEmpty()) {
                appendAssistantMessage(messages, response.content());
                List<ToolResult> toolResults = toolExecutor.executeAll(toolCalls);
                messages.addAll(toToolMessages(toolCalls, toolResults));
                continue;
            }

            JsonNode payload = toolCallParser.parseStructuredContent(response.content());
            String decision = payload == null ? "" : payload.path("decision").asText("");

            if ("DELIVER".equalsIgnoreCase(decision)) {
                return new ReviewResult(
                        sessionId,
                        extractFindings(payload.path("findings"), reviewTask),
                        false,
                        Instant.now()
                );
            }

            throw new IllegalStateException("Unsupported review decision %s for task %s".formatted(decision, reviewTask.taskId()));
        }

        return new ReviewResult(sessionId, List.of(), true, Instant.now());
    }

    private void requireWithinBudget(List<LlmMessage> messages, ContextPack contextPack) {
        int estimatedTokens = tokenCounter.countMessages(messages);
        if (estimatedTokens > contextPack.tokenBudget().totalTokens()) {
            throw new IllegalStateException("Estimated prompt tokens %d exceed budget %d"
                    .formatted(estimatedTokens, contextPack.tokenBudget().totalTokens()));
        }
    }

    private void appendAssistantMessage(List<LlmMessage> messages, String content) {
        if (content != null && !content.isBlank()) {
            messages.add(new LlmMessage("assistant", content));
        }
    }

    private List<LlmMessage> toToolMessages(List<ToolCall> toolCalls, List<ToolResult> toolResults) {
        List<LlmMessage> messages = new ArrayList<>();
        for (int index = 0; index < toolResults.size(); index++) {
            ToolCall toolCall = toolCalls.get(index);
            ToolResult toolResult = toolResults.get(index);
            String content = """
                    tool=%s
                    call_id=%s
                    success=%s
                    output:
                    %s
                    """.formatted(toolCall.toolName(), toolResult.callId(), toolResult.success(), toolResult.output());
            messages.add(new LlmMessage("tool", content));
        }
        return List.copyOf(messages);
    }

    private List<Finding> extractFindings(JsonNode findingsNode, ReviewTask reviewTask) {
        if (findingsNode == null || !findingsNode.isArray()) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        int index = 1;
        for (JsonNode findingNode : findingsNode) {
            String filePath = findingNode.path("file").asText();
            int startLine = intOrDefault(findingNode, "line", intOrDefault(findingNode, "start_line", 1));
            int endLine = intOrDefault(findingNode, "end_line", startLine);
            String category = textOrDefault(findingNode, "category", reviewTask.type().name().toLowerCase());
            String suggestion = textOrDefault(findingNode, "suggestion", "");
            double confidence = findingNode.path("confidence").isNumber()
                    ? findingNode.path("confidence").asDouble()
                    : 0.5d;

            findings.add(Finding.reported(
                    reviewTask.taskId() + "-finding-" + index,
                    reviewTask.taskId(),
                    category,
                    Severity.valueOf(textOrDefault(findingNode, "severity", "MEDIUM").toUpperCase()),
                    confidence,
                    new Finding.CodeLocation(filePath, startLine, endLine),
                    textOrDefault(findingNode, "title", "Untitled finding"),
                    textOrDefault(findingNode, "description", "No description provided."),
                    suggestion,
                    extractEvidence(findingNode.path("evidence"))
            ));
            index++;
        }
        return List.copyOf(findings);
    }

    private List<String> extractEvidence(JsonNode evidenceNode) {
        if (evidenceNode == null || !evidenceNode.isArray()) {
            return List.of();
        }

        List<String> evidence = new ArrayList<>();
        for (JsonNode entry : evidenceNode) {
            evidence.add(entry.asText());
        }
        return List.copyOf(evidence);
    }

    private int intOrDefault(JsonNode node, String fieldName, int defaultValue) {
        JsonNode value = node.path(fieldName);
        return value.isNumber() ? value.asInt() : defaultValue;
    }

    private String textOrDefault(JsonNode node, String fieldName, String defaultValue) {
        String value = node.path(fieldName).asText("");
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
