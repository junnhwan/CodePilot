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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ReviewEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewEngine.class);

    private final LlmClient llmClient;

    private final ToolRegistry toolRegistry;

    private final ToolExecutor toolExecutor;

    private final ToolCallParser toolCallParser;

    private final TokenCounter tokenCounter;

    private final ContextGovernor contextGovernor;

    private final LoopDetector loopDetector;

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
        this(
                llmClient,
                toolRegistry,
                toolExecutor,
                toolCallParser,
                tokenCounter,
                new ContextGovernor(tokenCounter),
                new LoopDetector(),
                model,
                llmParams,
                maxIterations
        );
    }

    public ReviewEngine(
            LlmClient llmClient,
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ToolCallParser toolCallParser,
            TokenCounter tokenCounter,
            ContextGovernor contextGovernor,
            LoopDetector loopDetector,
            String model,
            Map<String, Object> llmParams,
            int maxIterations
    ) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.toolCallParser = toolCallParser;
        this.tokenCounter = tokenCounter;
        this.contextGovernor = contextGovernor;
        this.loopDetector = loopDetector;
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
        Map<String, Finding> collectedFindings = new LinkedHashMap<>();
        LOGGER.info(
                "Starting review task sessionId={} taskId={} reviewer={} type={} model={} maxIterations={}",
                sessionId,
                reviewTask.taskId(),
                agentDefinition.agentName(),
                reviewTask.type(),
                model,
                maxIterations
        );

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            ContextGovernor.CompactionResult compactionResult = compactIfNeeded(messages, contextPack);
            if (!compactionResult.withinBudget()) {
                LOGGER.warn(
                        "Prompt budget exceeded after compaction sessionId={} taskId={} reviewer={} originalTokens={} compactedTokens={} budget={}",
                        sessionId,
                        reviewTask.taskId(),
                        agentDefinition.agentName(),
                        compactionResult.originalTokens(),
                        compactionResult.compactedTokens(),
                        promptBudget(contextPack)
                );
                return partialResult(sessionId, collectedFindings.values());
            }
            if (!compactionResult.appliedStrategies().isEmpty()) {
                LOGGER.info(
                        "Applied prompt compaction sessionId={} taskId={} reviewer={} strategies={} tokens={} -> {}",
                        sessionId,
                        reviewTask.taskId(),
                        agentDefinition.agentName(),
                        compactionResult.appliedStrategies(),
                        compactionResult.originalTokens(),
                        compactionResult.compactedTokens()
                );
            }
            messages = new ArrayList<>(compactionResult.messages());

            var response = llmClient.chat(new LlmRequest(model, List.copyOf(messages), toolRegistry.toolDefinitions(), llmParams));
            LOGGER.info(
                    "Received LLM response sessionId={} taskId={} reviewer={} iteration={} finishReason={} usageTotalTokens={}",
                    sessionId,
                    reviewTask.taskId(),
                    agentDefinition.agentName(),
                    iteration + 1,
                    response.finishReason(),
                    response.usage() == null ? null : response.usage().totalTokens()
            );
            List<ToolCall> toolCalls = toolCallParser.parse(response);

            if (!toolCalls.isEmpty()) {
                collectFindings(collectedFindings, tryParseStructuredContent(response.content()), reviewTask);
                LOGGER.info(
                        "Review iteration requested tools sessionId={} taskId={} reviewer={} iteration={} toolCount={} tools={} collectedFindings={}",
                        sessionId,
                        reviewTask.taskId(),
                        agentDefinition.agentName(),
                        iteration + 1,
                        toolCalls.size(),
                        toolNames(toolCalls),
                        collectedFindings.size()
                );
                messages.add(toAssistantToolCallMessage(toolCalls));
                List<ToolResult> toolResults = toolExecutor.executeAll(toolCalls);
                long successfulToolCalls = toolResults.stream().filter(ToolResult::success).count();
                LOGGER.info(
                        "Tool execution finished sessionId={} taskId={} reviewer={} iteration={} successCount={} failureCount={}",
                        sessionId,
                        reviewTask.taskId(),
                        agentDefinition.agentName(),
                        iteration + 1,
                        successfulToolCalls,
                        toolResults.size() - successfulToolCalls
                );
                messages.addAll(toToolMessages(toolCalls, toolResults));
                if (loopDetector.detect(messages).loopDetected()) {
                    LOGGER.warn(
                            "Loop detected while reviewing task sessionId={} taskId={} reviewer={} iteration={} collectedFindings={}",
                            sessionId,
                            reviewTask.taskId(),
                            agentDefinition.agentName(),
                            iteration + 1,
                            collectedFindings.size()
                    );
                    return partialResult(sessionId, collectedFindings.values());
                }
                continue;
            }

            JsonNode payload = toolCallParser.parseStructuredContent(response.content());
            collectFindings(collectedFindings, payload, reviewTask);
            String decision = payload == null ? "" : payload.path("decision").asText("");
            LOGGER.info(
                    "Review iteration completed sessionId={} taskId={} reviewer={} iteration={} decision={} collectedFindings={}",
                    sessionId,
                    reviewTask.taskId(),
                    agentDefinition.agentName(),
                    iteration + 1,
                    decision,
                    collectedFindings.size()
            );

            if ("DELIVER".equalsIgnoreCase(decision)) {
                LOGGER.info(
                        "Review task delivered sessionId={} taskId={} reviewer={} findings={} partial=false",
                        sessionId,
                        reviewTask.taskId(),
                        agentDefinition.agentName(),
                        collectedFindings.size()
                );
                return new ReviewResult(
                        sessionId,
                        List.copyOf(collectedFindings.values()),
                        false,
                        Instant.now()
                );
            }

            LOGGER.warn(
                    "Unsupported review decision sessionId={} taskId={} reviewer={} iteration={} decision={}",
                    sessionId,
                    reviewTask.taskId(),
                    agentDefinition.agentName(),
                    iteration + 1,
                    decision
            );
            throw new IllegalStateException("Unsupported review decision %s for task %s".formatted(decision, reviewTask.taskId()));
        }

        LOGGER.warn(
                "Review task exhausted max iterations sessionId={} taskId={} reviewer={} maxIterations={} collectedFindings={}",
                sessionId,
                reviewTask.taskId(),
                agentDefinition.agentName(),
                maxIterations,
                collectedFindings.size()
        );
        return partialResult(sessionId, collectedFindings.values());
    }

    private ContextGovernor.CompactionResult compactIfNeeded(List<LlmMessage> messages, ContextPack contextPack) {
        return contextGovernor.compact(messages, promptBudget(contextPack));
    }

    private int promptBudget(ContextPack contextPack) {
        return Math.max(contextPack.tokenBudget().totalTokens() - contextPack.tokenBudget().reservedTokens(), 0);
    }

    private ReviewResult partialResult(String sessionId, Iterable<Finding> findings) {
        List<Finding> collected = new ArrayList<>();
        findings.forEach(collected::add);
        return new ReviewResult(sessionId, List.copyOf(collected), true, Instant.now());
    }

    private JsonNode tryParseStructuredContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        try {
            return toolCallParser.parseStructuredContent(rawContent);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void collectFindings(Map<String, Finding> collectedFindings, JsonNode payload, ReviewTask reviewTask) {
        if (payload == null) {
            return;
        }
        for (Finding finding : extractFindings(payload.path("findings"), reviewTask)) {
            collectedFindings.putIfAbsent(findingKey(finding), finding);
        }
    }

    private String findingKey(Finding finding) {
        return finding.location().filePath()
                + ":"
                + finding.location().startLine()
                + ":"
                + finding.location().endLine()
                + ":"
                + finding.category()
                + ":"
                + finding.title();
    }

    private List<String> toolNames(List<ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(ToolCall::toolName)
                .toList();
    }

    private LlmMessage toAssistantToolCallMessage(List<ToolCall> toolCalls) {
        StringBuilder builder = new StringBuilder("decision=CALL_TOOL").append(System.lineSeparator());
        for (ToolCall toolCall : toolCalls) {
            builder.append("call_id=").append(toolCall.callId()).append(System.lineSeparator());
            builder.append("signature=").append(toolCall.signature()).append(System.lineSeparator());
            builder.append("tool=").append(toolCall.toolName()).append(System.lineSeparator());
            builder.append("arguments=").append(new TreeMap<>(toolCall.arguments())).append(System.lineSeparator());
        }
        return new LlmMessage("assistant", builder.toString().trim());
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
