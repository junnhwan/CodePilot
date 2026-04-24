package com.codepilot.eval;

import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.plan.ReviewTask;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunnerTest {

    @Test
    void runsDefaultScenarioPackThroughPlanningAndMultiAgentReview() {
        EvalScenarioLoader loader = new EvalScenarioLoader();
        EvalRunner runner = new EvalRunner(new FixtureAwareLlmClient());

        EvalRunner.RunResult runResult = runner.run(loader.loadDefaultScenarios());

        assertThat(runResult.baseline()).isEqualTo(EvalBaseline.CODEPILOT);
        assertThat(runResult.scenarioResults()).hasSize(4);
        assertThat(runResult.scenarioResults()).allMatch(EvalRunner.ScenarioResult::successful);
        assertThat(runResult.scenarioResults()).allMatch(EvalRunner.ScenarioResult::passed);
        assertThat(runResult.scorecard().scenariosPassed()).isEqualTo(4);
        assertThat(runResult.scorecard().scenariosFailed()).isZero();
        assertThat(runResult.scorecard().scenariosError()).isZero();
        assertThat(runResult.scorecard().metrics().precision()).isEqualTo(1.0d);
        assertThat(runResult.scorecard().metrics().recall()).isEqualTo(1.0d);
        assertThat(runResult.scorecard().metrics().partialRunRate()).isEqualTo(0.0d);
        assertThat(runResult.scorecard().metrics().avgContextTokensUsed()).isGreaterThan(0.0d);
        assertThat(runResult.scorecard().metrics().avgTokenEfficiency()).isPositive();
        assertThat(runResult.scorecard().byCategory().get("SECURITY").recall()).isEqualTo(1.0d);
        assertThat(runResult.scorecard().byCategory().get("PERF").precision()).isEqualTo(1.0d);
    }

    @Test
    void capturesScenarioAndBaselineInErrorOutput() {
        EvalScenario scenario = new EvalScenario(
                "eval-error-001",
                "Broken baseline scenario",
                "Triggers a baseline error for assertion.",
                "eval-error-project",
                null,
                List.of(new EvalScenario.RepositoryFile(
                        "src/main/java/com/example/Broken.java",
                        List.of("class Broken {}")
                )),
                List.of("diff --git a/src/main/java/com/example/Broken.java b/src/main/java/com/example/Broken.java"),
                null,
                List.of(),
                new EvalScenario.StopPolicy(6, 5)
        );

        EvalRunner.RunResult runResult = new EvalRunner(new LlmClient() {
            @Override
            public LlmResponse chat(LlmRequest request) {
                throw new IllegalStateException("boom");
            }

            @Override
            public Flux<LlmChunk> stream(LlmRequest request) {
                throw new UnsupportedOperationException("stream is not used in this test");
            }
        }).run(List.of(scenario), EvalBaseline.DIRECT_LLM);

        assertThat(runResult.scorecard().scenariosError()).isEqualTo(1);
        assertThat(runResult.scenarioResults()).singleElement()
                .extracting(EvalRunner.ScenarioResult::errorMessage)
                .asString()
                .contains("scenarioId=eval-error-001")
                .contains("baseline=DIRECT_LLM");
    }

    private static final class FixtureAwareLlmClient implements LlmClient {

        private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

        @Override
        public LlmResponse chat(LlmRequest request) {
            ReviewTask.TaskType taskType = taskType(request.messages());
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
            return deliver("""
                    {
                      "decision": "DELIVER",
                      "findings": []
                    }
                    """);
        }

        @Override
        public Flux<LlmChunk> stream(LlmRequest request) {
            throw new UnsupportedOperationException("stream is not used in this test");
        }

        private ReviewTask.TaskType taskType(List<LlmMessage> messages) {
            String systemPrompt = messages.stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(LlmMessage::content)
                    .findFirst()
                    .orElseThrow();
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
}
