package com.codepilot.core.application.review;

import com.codepilot.core.application.tool.ToolExecutor;
import com.codepilot.core.application.tool.ToolRegistry;
import com.codepilot.core.domain.agent.AgentDecision;
import com.codepilot.core.domain.agent.AgentDefinition;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.context.ImpactSet;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.llm.ToolCallInResponse;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewEngineTest {

    @TempDir
    Path repoRoot;

    @Test
    void executesSingleAgentLoopAndReturnsReportedFinding() throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/UserRepository.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                class UserRepository {
                    String findByName(String name) {
                        return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", String.class);
                    }
                }
                """);

        ToolRegistry toolRegistry = new ToolRegistry(List.of(new ReadFileTool(repoRoot)));
        TokenCounter tokenCounter = new TokenCounter();
        ReviewEngine engine = new ReviewEngine(
                new StubLlmClient(List.of(
                        new LlmResponse(
                                "",
                                List.of(new ToolCallInResponse(
                                        "call-1",
                                        "read_file",
                                        Map.of("file_path", "src/main/java/com/example/UserRepository.java")
                                )),
                                new LlmUsage(120, 18, 138),
                                "tool_calls"
                        ),
                        new LlmResponse(
                                """
                                {
                                  "decision": "DELIVER",
                                  "findings": [
                                    {
                                      "file": "src/main/java/com/example/UserRepository.java",
                                      "line": 5,
                                      "severity": "HIGH",
                                      "confidence": 0.98,
                                      "category": "security",
                                      "title": "SQL injection risk in query construction",
                                      "description": "User input is concatenated directly into the SQL statement.",
                                      "suggestion": "Use a parameterized query instead of string concatenation.",
                                      "evidence": [
                                        "The SQL string includes '+ name +' before execution."
                                      ]
                                    }
                                  ]
                                }
                                """,
                                List.of(),
                                new LlmUsage(160, 92, 252),
                                "stop"
                        )
                )),
                toolRegistry,
                new ToolExecutor(toolRegistry),
                new ToolCallParser(JsonMapper.builder().findAndAddModules().build()),
                tokenCounter,
                new ContextGovernor(tokenCounter),
                new LoopDetector(),
                "mock-review-model",
                Map.of(),
                4
        );

        ReviewResult reviewResult = engine.execute(
                "session-1",
                new AgentDefinition(
                        "security-reviewer",
                        "Review changed code for security flaws",
                        Set.of(AgentState.REVIEWING),
                        Set.of(AgentDecision.Type.CALL_TOOL, AgentDecision.Type.DELIVER),
                        List.of("SQL injection", "unsafe input handling")
                ),
                ReviewTask.pending(
                        "task-security",
                        ReviewTask.TaskType.SECURITY,
                        ReviewTask.Priority.HIGH,
                        List.of("src/main/java/com/example/UserRepository.java"),
                        List.of("Review database access code for injection risks"),
                        List.of()
                ),
                contextPack("src/main/java/com/example/UserRepository.java", 8000, 1000)
        );

        assertThat(reviewResult.partial()).isFalse();
        assertThat(reviewResult.generatedAt()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(reviewResult.findings()).hasSize(1);
        assertThat(reviewResult.findings().getFirst().status().name()).isEqualTo("REPORTED");
        assertThat(reviewResult.findings().getFirst().severity()).isEqualTo(Severity.HIGH);
        assertThat(reviewResult.findings().getFirst().location().filePath())
                .isEqualTo("src/main/java/com/example/UserRepository.java");
    }

    @Test
    void compactsConversationBeforeNextLlmCallWhenPromptBudgetIsExceeded() throws IOException {
        Path largeFile = repoRoot.resolve("A.java");
        Files.createDirectories(largeFile.getParent());
        Files.writeString(largeFile, "A".repeat(1200));

        Path smallFile = repoRoot.resolve("B.java");
        Files.writeString(smallFile, "safe-query");

        ToolRegistry toolRegistry = new ToolRegistry(List.of(new ReadFileTool(repoRoot)));
        TokenCounter tokenCounter = new TokenCounter();
        StubLlmClient llmClient = new StubLlmClient(List.of(
                new LlmResponse(
                        "",
                        List.of(new ToolCallInResponse(
                                "call-1",
                                "read_file",
                                Map.of("file_path", "A.java")
                        )),
                        new LlmUsage(90, 18, 108),
                        "tool_calls"
                ),
                new LlmResponse(
                        "",
                        List.of(new ToolCallInResponse(
                                "call-2",
                                "read_file",
                                Map.of("file_path", "B.java")
                        )),
                        new LlmUsage(98, 20, 118),
                        "tool_calls"
                ),
                new LlmResponse(
                        """
                        {
                          "decision": "DELIVER",
                          "findings": []
                        }
                        """,
                        List.of(),
                        new LlmUsage(72, 12, 84),
                        "stop"
                )
        ));
        ReviewEngine engine = new ReviewEngine(
                llmClient,
                toolRegistry,
                new ToolExecutor(toolRegistry),
                new ToolCallParser(JsonMapper.builder().findAndAddModules().build()),
                tokenCounter,
                new ContextGovernor(tokenCounter),
                new LoopDetector(),
                "mock-review-model",
                Map.of(),
                5
        );

        ReviewResult reviewResult = engine.execute(
                "session-compact",
                securityAgent(),
                ReviewTask.pending(
                        "task-compact",
                        ReviewTask.TaskType.SECURITY,
                        ReviewTask.Priority.HIGH,
                        List.of("A.java"),
                        List.of("Check"),
                        List.of()
                ),
                new ContextPack(
                        Map.of("language", "java"),
                        DiffSummary.of(List.of(new DiffSummary.ChangedFile(
                                "A.java",
                                DiffSummary.ChangeType.MODIFIED,
                                1,
                                0,
                                List.of("A#m")
                        ))),
                        new ImpactSet(Set.of("A.java"), Set.of(), List.of()),
                        List.of(new ContextPack.CodeSnippet(
                                "A.java",
                                1,
                                1,
                                "x",
                                "Changed hunk"
                        )),
                        ProjectMemory.empty("project-alpha"),
                        new ContextPack.TokenBudget(530, 40, 120)
                )
        );

        assertThat(reviewResult.partial()).isFalse();
        assertThat(llmClient.capturedRequests()).hasSize(3);
        LlmRequest compactedRequest = llmClient.capturedRequests().get(2);
        assertThat(tokenCounter.countMessages(compactedRequest.messages())).isLessThanOrEqualTo(490);
        assertThat(compactedRequest.messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .map(LlmMessage::content))
                .anyMatch(content -> content.contains("call-2"));
        assertThat(compactedRequest.messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .map(LlmMessage::content))
                .noneMatch(content -> content.contains("call-1"));
    }

    @Test
    void degradesToPartialResultWhenRepeatedToolCallsTriggerLoopDetection() throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/UserRepository.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "class UserRepository {}");

        ToolRegistry toolRegistry = new ToolRegistry(List.of(new ReadFileTool(repoRoot)));
        TokenCounter tokenCounter = new TokenCounter();
        StubLlmClient llmClient = new StubLlmClient(List.of(
                repeatedToolCallResponse(),
                repeatedToolCallResponse(),
                repeatedToolCallResponse()
        ));
        ReviewEngine engine = new ReviewEngine(
                llmClient,
                toolRegistry,
                new ToolExecutor(toolRegistry),
                new ToolCallParser(JsonMapper.builder().findAndAddModules().build()),
                tokenCounter,
                new ContextGovernor(tokenCounter),
                new LoopDetector(3),
                "mock-review-model",
                Map.of(),
                6
        );

        ReviewResult reviewResult = engine.execute(
                "session-loop",
                securityAgent(),
                reviewTask("task-loop", "src/main/java/com/example/UserRepository.java"),
                contextPack("src/main/java/com/example/UserRepository.java", 8000, 1000)
        );

        assertThat(reviewResult.partial()).isTrue();
        assertThat(reviewResult.findings()).hasSize(1);
        assertThat(reviewResult.findings().getFirst().title()).isEqualTo("Potential SQL injection risk");
        assertThat(llmClient.capturedRequests()).hasSize(3);
    }

    private static LlmResponse repeatedToolCallResponse() {
        return new LlmResponse(
                """
                {
                  "decision": "CALL_TOOL",
                  "tool_calls": [
                    {
                      "tool": "read_file",
                      "arguments": {
                        "file_path": "src/main/java/com/example/UserRepository.java"
                      }
                    }
                  ],
                  "findings": [
                    {
                      "file": "src/main/java/com/example/UserRepository.java",
                      "line": 1,
                      "severity": "HIGH",
                      "confidence": 0.91,
                      "category": "security",
                      "title": "Potential SQL injection risk",
                      "description": "The repository still needs verification of input handling.",
                      "suggestion": "Review how user input reaches the query layer.",
                      "evidence": [
                        "The agent keeps asking for the same repository file."
                      ]
                    }
                  ]
                }
                """,
                List.of(),
                new LlmUsage(84, 30, 114),
                "stop"
        );
    }

    private static AgentDefinition securityAgent() {
        return new AgentDefinition(
                "security-reviewer",
                "Review changed code for security flaws",
                Set.of(AgentState.REVIEWING),
                Set.of(AgentDecision.Type.CALL_TOOL, AgentDecision.Type.DELIVER),
                List.of("SQL injection", "unsafe input handling")
        );
    }

    private static ReviewTask reviewTask(String taskId, String targetFile) {
        return ReviewTask.pending(
                taskId,
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                List.of(targetFile),
                List.of("Review database access code for injection risks"),
                List.of()
        );
    }

    private static ContextPack contextPack(String targetFile, int totalTokens, int reservedTokens) {
        return new ContextPack(
                Map.of("language", "java", "framework", "spring"),
                DiffSummary.of(List.of(new DiffSummary.ChangedFile(
                        targetFile,
                        DiffSummary.ChangeType.MODIFIED,
                        4,
                        0,
                        List.of("UserRepository#findByName")
                ))),
                new ImpactSet(Set.of(targetFile), Set.of(), List.of()),
                List.of(new ContextPack.CodeSnippet(
                        targetFile,
                        1,
                        3,
                        "return jdbcTemplate.queryForObject(\"select * from users where name = ?\", String.class);",
                        "Changed hunk"
                )),
                ProjectMemory.empty("project-alpha"),
                new ContextPack.TokenBudget(totalTokens, reservedTokens, Math.min(320, totalTokens))
        );
    }

    private static final class StubLlmClient implements LlmClient {

        private final List<LlmResponse> scriptedResponses;

        private final List<LlmRequest> capturedRequests = new ArrayList<>();

        private int cursor;

        private StubLlmClient(List<LlmResponse> scriptedResponses) {
            this.scriptedResponses = List.copyOf(scriptedResponses);
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            capturedRequests.add(request);
            return scriptedResponses.get(cursor++);
        }

        @Override
        public Flux<LlmChunk> stream(LlmRequest request) {
            throw new UnsupportedOperationException("stream is not used in this test");
        }

        private List<LlmRequest> capturedRequests() {
            return List.copyOf(capturedRequests);
        }
    }
}
