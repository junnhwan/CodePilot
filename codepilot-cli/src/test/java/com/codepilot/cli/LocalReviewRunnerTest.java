package com.codepilot.cli;

import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.plan.ReviewTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReviewRunnerTest {

    @TempDir
    Path repoRoot;

    @Test
    void buildsReviewContextFromDiffAndReturnsFinding() throws IOException {
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
        Path diffFile = repoRoot.resolve("changes.diff");
        Files.writeString(diffFile, """
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
                """);

        TaskAwareLlmClient llmClient = new TaskAwareLlmClient();
        LocalReviewRunner runner = new LocalReviewRunner(llmClient);

        var result = runner.run(diffFile, repoRoot);

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().title()).contains("SQL injection");
        assertThat(result.findings().getFirst().taskId()).isEqualTo("task-security");
        assertThat(llmClient.seenTaskTypes())
                .contains(
                        ReviewTask.TaskType.SECURITY,
                        ReviewTask.TaskType.PERF,
                        ReviewTask.TaskType.STYLE,
                        ReviewTask.TaskType.MAINTAIN
                );
    }

    private static final class TaskAwareLlmClient implements LlmClient {

        private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

        private final List<ReviewTask.TaskType> seenTaskTypes = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse chat(LlmRequest request) {
            ReviewTask.TaskType taskType = taskType(request.messages());
            seenTaskTypes.add(taskType);
            if (taskType == ReviewTask.TaskType.SECURITY) {
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

        private List<ReviewTask.TaskType> seenTaskTypes() {
            return List.copyOf(seenTaskTypes);
        }
    }
}
