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
                new TokenCounter(),
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
                new ContextPack(
                        Map.of("language", "java", "framework", "spring"),
                        DiffSummary.of(List.of(new DiffSummary.ChangedFile(
                                "src/main/java/com/example/UserRepository.java",
                                DiffSummary.ChangeType.MODIFIED,
                                4,
                                0,
                                List.of("UserRepository#findByName")
                        ))),
                        new ImpactSet(Set.of("src/main/java/com/example/UserRepository.java"), Set.of(), List.of()),
                        List.of(new ContextPack.CodeSnippet(
                                "src/main/java/com/example/UserRepository.java",
                                3,
                                5,
                                "return jdbcTemplate.queryForObject(\"select * from users where name = '\" + name + \"'\", String.class);",
                                "Changed hunk"
                        )),
                        ProjectMemory.empty("project-alpha"),
                        new ContextPack.TokenBudget(8000, 1000, 320)
                )
        );

        assertThat(reviewResult.partial()).isFalse();
        assertThat(reviewResult.generatedAt()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(reviewResult.findings()).hasSize(1);
        assertThat(reviewResult.findings().getFirst().status().name()).isEqualTo("REPORTED");
        assertThat(reviewResult.findings().getFirst().severity()).isEqualTo(Severity.HIGH);
        assertThat(reviewResult.findings().getFirst().location().filePath())
                .isEqualTo("src/main/java/com/example/UserRepository.java");
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
    }
}
