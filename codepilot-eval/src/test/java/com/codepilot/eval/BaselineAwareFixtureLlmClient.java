package com.codepilot.eval;

import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.plan.ReviewTask;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BaselineAwareFixtureLlmClient implements LlmClient {

    private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

    @Override
    public LlmResponse chat(LlmRequest request) {
        String systemPrompt = request.messages().stream()
                .filter(message -> "system".equals(message.role()))
                .map(LlmMessage::content)
                .findFirst()
                .orElseThrow();
        String userPrompt = request.messages().stream()
                .filter(message -> "user".equals(message.role()))
                .map(LlmMessage::content)
                .findFirst()
                .orElseThrow();

        if (systemPrompt.contains("Baseline mode: DIRECT_LLM")) {
            return directBaseline(userPrompt);
        }
        if (systemPrompt.contains("Baseline mode: FULL_CONTEXT_LLM")) {
            return fullContextBaseline(userPrompt);
        }

        ReviewTask.TaskType taskType = taskType(systemPrompt);
        return codePilot(taskType, systemPrompt, userPrompt);
    }

    @Override
    public Flux<LlmChunk> stream(LlmRequest request) {
        throw new UnsupportedOperationException("stream is not used in eval tests");
    }

    private LlmResponse codePilot(ReviewTask.TaskType taskType, String systemPrompt, String userPrompt) {
        if (taskType == ReviewTask.TaskType.SECURITY
                && userPrompt.contains("select * from users where name = '")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/user/UserRepository.java",
                          "line": 6,
                          "severity": "HIGH",
                          "confidence": 0.97,
                          "category": "security",
                          "title": "SQL injection risk",
                          "description": "User input is concatenated into SQL.",
                          "suggestion": "Use a parameterized query.",
                          "evidence": ["The query string directly concatenates name into SQL."]
                        }
                      ]
                    }
                    """);
        }
        if (taskType == ReviewTask.TaskType.SECURITY
                && userPrompt.contains("ghp_demo_insecure_token")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/github/GitHubWebhookClient.java",
                          "line": 4,
                          "severity": "HIGH",
                          "confidence": 0.94,
                          "category": "security",
                          "title": "Hardcoded API token",
                          "description": "A fallback GitHub token is hardcoded in source code.",
                          "suggestion": "Load the token from a secret store or environment variable.",
                          "evidence": ["The diff assigns FALLBACK_GITHUB_TOKEN to a literal value."]
                        }
                      ]
                    }
                    """);
        }
        if (taskType == ReviewTask.TaskType.PERF
                && userPrompt.contains("orderRepository.findById(orderId)")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/order/OrderService.java",
                          "line": 10,
                          "severity": "MEDIUM",
                          "confidence": 0.93,
                          "category": "perf",
                          "title": "Repository call inside loop",
                          "description": "The diff introduces one repository lookup per order id.",
                          "suggestion": "Batch load the orders outside the loop.",
                          "evidence": ["The changed loop performs orderRepository.findById(orderId) on each iteration."]
                        }
                      ]
                    }
                    """);
        }
        if (taskType == ReviewTask.TaskType.PERF
                && userPrompt.contains("invoiceRepository.findById(invoiceId)")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/invoice/InvoiceAggregationService.java",
                          "line": 10,
                          "severity": "MEDIUM",
                          "confidence": 0.92,
                          "category": "perf",
                          "title": "Repository call inside loop",
                          "description": "The diff introduces one repository lookup per invoice id.",
                          "suggestion": "Batch load the invoices outside the loop.",
                          "evidence": ["The changed loop performs invoiceRepository.findById(invoiceId) on each iteration."]
                        }
                      ]
                    }
                    """);
        }
        if (taskType == ReviewTask.TaskType.SECURITY
                && systemPrompt.contains("Project token lookups must call TokenGuard before repository access")
                && userPrompt.contains("findByProjectToken(projectToken)")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/token/ProjectTokenService.java",
                          "line": 5,
                          "severity": "HIGH",
                          "confidence": 0.91,
                          "category": "security",
                          "title": "Missing token guard before repository access",
                          "description": "The project token reaches tokenRepository without the known TokenGuard check.",
                          "suggestion": "Call tokenGuard.requireProjectAccess(projectToken) before the repository lookup.",
                          "evidence": ["The diff reads from tokenRepository directly and the recalled memory pattern requires TokenGuard first."]
                        }
                      ]
                    }
                    """);
        }
        if (taskType == ReviewTask.TaskType.MAINTAIN
                && userPrompt.contains("catch (IOException error)")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/audit/WebhookAuditWriter.java",
                          "line": 9,
                          "severity": "MEDIUM",
                          "confidence": 0.89,
                          "category": "maintain",
                          "title": "Exception swallowed",
                          "description": "The catch block ignores IOException instead of surfacing or logging it.",
                          "suggestion": "Log the exception or rethrow it as an explicit failure.",
                          "evidence": ["The added catch block contains only an ignore comment."]
                        }
                      ]
                    }
                    """);
        }
        return deliver("""
                {
                  "decision": "DELIVER",
                  "findings": []
                }
                """);
    }

    private LlmResponse directBaseline(String userPrompt) {
        if (userPrompt.contains("select * from users where name = '")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/user/UserRepository.java",
                          "line": 6,
                          "severity": "HIGH",
                          "confidence": 0.92,
                          "category": "security",
                          "title": "SQL injection risk",
                          "description": "The diff concatenates untrusted input into SQL.",
                          "suggestion": "Use placeholders instead of string concatenation.",
                          "evidence": ["The SQL string includes '+ name +' directly."]
                        }
                      ]
                    }
                    """);
        }
        if (userPrompt.contains("normalizedUserName")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/profile/ProfileFormatter.java",
                          "line": 5,
                          "severity": "LOW",
                          "confidence": 0.63,
                          "category": "style",
                          "title": "Variable naming could be clearer",
                          "description": "The rename still looks noisy.",
                          "suggestion": "Shorten the local variable name.",
                          "evidence": ["The renamed variable is longer than before."]
                        }
                      ]
                    }
                    """);
        }
        return deliver("""
                {
                  "decision": "DELIVER",
                  "findings": []
                }
                """);
    }

    private LlmResponse fullContextBaseline(String userPrompt) {
        if (userPrompt.contains("select * from users where name = '")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/user/UserRepository.java",
                          "line": 6,
                          "severity": "HIGH",
                          "confidence": 0.95,
                          "category": "security",
                          "title": "SQL injection risk",
                          "description": "The repository query concatenates user input into SQL.",
                          "suggestion": "Use a bound parameter.",
                          "evidence": ["The repository code builds SQL with user-controlled input."]
                        }
                      ]
                    }
                    """);
        }
        if (userPrompt.contains("orderRepository.findById(orderId)")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/order/OrderService.java",
                          "line": 10,
                          "severity": "MEDIUM",
                          "confidence": 0.88,
                          "category": "perf",
                          "title": "Repository call inside loop",
                          "description": "Each iteration performs a repository lookup.",
                          "suggestion": "Batch the lookup or prefetch outside the loop.",
                          "evidence": ["The for-loop invokes orderRepository.findById(orderId) every time."]
                        }
                      ]
                    }
                    """);
        }
        if (userPrompt.contains("invoiceRepository.findById(invoiceId)")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/invoice/InvoiceAggregationService.java",
                          "line": 10,
                          "severity": "MEDIUM",
                          "confidence": 0.86,
                          "category": "perf",
                          "title": "Repository call inside loop",
                          "description": "Each iteration performs a repository lookup.",
                          "suggestion": "Batch the lookup or prefetch outside the loop.",
                          "evidence": ["The for-loop invokes invoiceRepository.findById(invoiceId) every time."]
                        }
                      ]
                    }
                    """);
        }
        if (userPrompt.contains("ghp_demo_insecure_token")) {
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": [
                        {
                          "file": "src/main/java/com/example/github/GitHubWebhookClient.java",
                          "line": 4,
                          "severity": "HIGH",
                          "confidence": 0.9,
                          "category": "security",
                          "title": "Hardcoded API token",
                          "description": "A credential-like token is committed in source code.",
                          "suggestion": "Move the token to runtime configuration or a secret manager.",
                          "evidence": ["FALLBACK_GITHUB_TOKEN is assigned to a hardcoded literal."]
                        }
                      ]
                    }
                    """);
        }
        return deliver("""
                {
                  "decision": "DELIVER",
                  "findings": []
                }
                """);
    }

    private ReviewTask.TaskType taskType(String systemPrompt) {
        Matcher matcher = TASK_TYPE_PATTERN.matcher(systemPrompt);
        if (!matcher.find()) {
            throw new IllegalStateException("Unable to locate task type in system prompt");
        }
        return ReviewTask.TaskType.valueOf(matcher.group(1));
    }

    private LlmResponse deliver(String content) {
        return new LlmResponse(content, List.of(), new LlmUsage(180, 90, 270), "stop");
    }
}
