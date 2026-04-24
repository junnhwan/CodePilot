package com.codepilot.eval;

import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.application.review.ToolCallParser;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class EvalBaselineReviewer {

    private final LlmClient llmClient;

    private final ToolCallParser toolCallParser;

    private final TokenCounter tokenCounter;

    private final String model;

    private final Map<String, Object> llmParams;

    EvalBaselineReviewer(
            LlmClient llmClient,
            ToolCallParser toolCallParser,
            TokenCounter tokenCounter,
            String model,
            Map<String, Object> llmParams
    ) {
        this.llmClient = llmClient;
        this.toolCallParser = toolCallParser;
        this.tokenCounter = tokenCounter;
        this.model = model;
        this.llmParams = llmParams == null ? Map.of() : Map.copyOf(llmParams);
    }

    ReviewOutcome review(EvalBaseline baseline, String sessionId, EvalScenario scenario) {
        return switch (baseline) {
            case DIRECT_LLM -> invoke(sessionId, directSystemPrompt(), directUserPrompt(scenario));
            case FULL_CONTEXT_LLM -> invoke(sessionId, fullContextSystemPrompt(), fullContextUserPrompt(scenario));
            case CODEPILOT -> throw new IllegalArgumentException("CODEPILOT baseline must use EvalRunner orchestrator path");
        };
    }

    private ReviewOutcome invoke(String sessionId, String systemPrompt, String userPrompt) {
        int promptTokensUsed = tokenCounter.countText(systemPrompt) + tokenCounter.countText(userPrompt);
        JsonNode payload = toolCallParser.parseStructuredContent(llmClient.chat(new LlmRequest(
                model,
                List.of(
                        new LlmMessage("system", systemPrompt),
                        new LlmMessage("user", userPrompt)
                ),
                List.of(),
                llmParams
        )).content());
        String decision = payload == null ? "" : payload.path("decision").asText("");
        if (!"DELIVER".equalsIgnoreCase(decision)) {
            throw new IllegalStateException("Eval baseline expected DELIVER but received " + decision);
        }

        return new ReviewOutcome(
                new ReviewResult(
                        sessionId,
                        extractFindings(payload.path("findings"), sessionId),
                        false,
                        Instant.now()
                ),
                promptTokensUsed
        );
    }

    private String directSystemPrompt() {
        return """
                You are a code review baseline.
                Baseline mode: DIRECT_LLM

                Review only the diff provided by the user.
                Do not assume hidden repository context, project memory, or tools.

                Return JSON only:
                {"decision":"DELIVER","findings":[{"file":"...","line":1,"severity":"HIGH","confidence":0.9,"category":"security","title":"...","description":"...","suggestion":"...","evidence":["..."]}]}
                """;
    }

    private String directUserPrompt(EvalScenario scenario) {
        return """
                Scenario:
                %s

                Diff:
                %s
                """.formatted(scenario.description(), scenario.rawDiff());
    }

    private String fullContextSystemPrompt() {
        return """
                You are a code review baseline.
                Baseline mode: FULL_CONTEXT_LLM

                Review the diff with the full repository context provided by the user.
                Do not use project memory or tools.

                Return JSON only:
                {"decision":"DELIVER","findings":[{"file":"...","line":1,"severity":"HIGH","confidence":0.9,"category":"security","title":"...","description":"...","suggestion":"...","evidence":["..."]}]}
                """;
    }

    private String fullContextUserPrompt(EvalScenario scenario) {
        String repositoryFiles = scenario.repositoryFiles().stream()
                .map(file -> """
                        File: %s
                        %s
                        """.formatted(file.path(), file.content()))
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));

        return """
                Scenario:
                %s

                Diff:
                %s

                Repository files:
                %s
                """.formatted(scenario.description(), scenario.rawDiff(), repositoryFiles);
    }

    private List<Finding> extractFindings(JsonNode findingsNode, String sessionId) {
        if (findingsNode == null || !findingsNode.isArray()) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        int index = 1;
        for (JsonNode findingNode : findingsNode) {
            String filePath = findingNode.path("file").asText();
            int startLine = intOrDefault(findingNode, "line", intOrDefault(findingNode, "start_line", 1));
            int endLine = intOrDefault(findingNode, "end_line", startLine);
            findings.add(Finding.reported(
                    sessionId + "-baseline-finding-" + index,
                    sessionId + "-baseline-task",
                    textOrDefault(findingNode, "category", "maintain"),
                    Severity.valueOf(textOrDefault(findingNode, "severity", "MEDIUM").toUpperCase()),
                    findingNode.path("confidence").isNumber() ? findingNode.path("confidence").asDouble() : 0.5d,
                    new Finding.CodeLocation(filePath, startLine, endLine),
                    textOrDefault(findingNode, "title", "Untitled finding"),
                    textOrDefault(findingNode, "description", "No description provided."),
                    textOrDefault(findingNode, "suggestion", ""),
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

    record ReviewOutcome(
            ReviewResult reviewResult,
            int contextTokensUsed
    ) {
    }
}
