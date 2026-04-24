package com.codepilot.mcp.review;

import com.codepilot.core.application.context.DefaultContextCompiler;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.context.ImpactCalculator;
import com.codepilot.core.application.memory.MemoryService;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class McpReviewServiceTest {

    @TempDir
    Path repoRoot;

    @Test
    void reviewsUnifiedDiffByReusingTheExistingReviewPipeline() throws IOException {
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
        String rawDiff = """
                diff --git a/src/main/java/com/example/UserRepository.java b/src/main/java/com/example/UserRepository.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/UserRepository.java
                +++ b/src/main/java/com/example/UserRepository.java
                @@ -2,3 +2,5 @@
                 class UserRepository {
                +    String findByName(String name) {
                +        return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", String.class);
                +    }
                 }
                """;

        McpReviewService service = testReviewService();

        McpReviewService.ReviewResponse response = service.reviewDiff(new McpReviewService.ReviewDiffRequest(
                repoRoot,
                rawDiff,
                "project-alpha",
                Map.of("entrypoint", "mcp-test")
        ));

        assertThat(response.reviewPlan().strategy().name()).isEqualTo("PERFORMANCE_FIRST");
        assertThat(response.reviewResult().findings()).hasSize(1);
        assertThat(response.reviewResult().findings().getFirst().title()).contains("SQL injection");
        assertThat(response.reviewResult().partial()).isFalse();
    }

    private McpReviewService testReviewService() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        TokenCounter tokenCounter = new TokenCounter();
        DiffAnalyzer diffAnalyzer = new DiffAnalyzer();
        ContextCompiler contextCompiler = new DefaultContextCompiler(
                diffAnalyzer,
                new JavaParserAstParser(),
                new ImpactCalculator(),
                tokenCounter,
                new ClasspathCompilationStrategyLoader(objectMapper).load("java-springboot-maven"),
                new MemoryService(tokenCounter)
        );
        ProjectMemoryRepository projectMemoryRepository = new ProjectMemoryRepository() {
            @Override
            public Optional<ProjectMemory> findByProjectId(String projectId) {
                return Optional.of(ProjectMemory.empty(projectId));
            }

            @Override
            public void save(ProjectMemory projectMemory) {
                throw new UnsupportedOperationException("save is not used in this test");
            }
        };
        return new McpReviewService(
                new SecurityOnlyLlmClient(),
                projectMemoryRepository,
                diffAnalyzer,
                contextCompiler,
                objectMapper,
                tokenCounter,
                "mock-review-model",
                Map.of(),
                6
        );
    }

    private static final class SecurityOnlyLlmClient implements LlmClient {

        private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

        @Override
        public LlmResponse chat(LlmRequest request) {
            if (taskType(request.messages()) == com.codepilot.core.domain.plan.ReviewTask.TaskType.SECURITY) {
                return new LlmResponse(
                        """
                        {
                          "decision": "DELIVER",
                          "findings": [
                            {
                              "file": "src/main/java/com/example/UserRepository.java",
                              "line": 4,
                              "severity": "HIGH",
                              "confidence": 0.95,
                              "category": "security",
                              "title": "SQL injection risk in query construction",
                              "description": "The query concatenates untrusted input directly into SQL.",
                              "suggestion": "Bind the user input as a parameter.",
                              "evidence": [
                                "The changed line includes '+ name +' inside the SQL string."
                              ]
                            }
                          ]
                        }
                        """,
                        List.of(),
                        new LlmUsage(140, 90, 230),
                        "stop"
                );
            }
            return new LlmResponse("""
                    {
                      "decision": "DELIVER",
                      "findings": []
                    }
                    """, List.of(), new LlmUsage(80, 20, 100), "stop");
        }

        @Override
        public Flux<LlmChunk> stream(LlmRequest request) {
            throw new UnsupportedOperationException("stream is not used in this test");
        }

        private com.codepilot.core.domain.plan.ReviewTask.TaskType taskType(List<LlmMessage> messages) {
            String systemPrompt = messages.stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(LlmMessage::content)
                    .findFirst()
                    .orElseThrow();
            Matcher matcher = TASK_TYPE_PATTERN.matcher(systemPrompt);
            if (!matcher.find()) {
                throw new IllegalStateException("Unable to locate task type in system prompt");
            }
            return com.codepilot.core.domain.plan.ReviewTask.TaskType.valueOf(matcher.group(1));
        }
    }
}
