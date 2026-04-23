package com.codepilot.cli;

import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.ToolCallInResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

        LocalReviewRunner runner = new LocalReviewRunner(new StubLlmClient(List.of(
                new LlmResponse(
                        "",
                        List.of(new ToolCallInResponse(
                                "call-1",
                                "read_file",
                                Map.of("file_path", "src/main/java/com/example/UserRepository.java")
                        )),
                        null,
                        "tool_calls"
                ),
                new LlmResponse(
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
                        null,
                        "stop"
                )
        )));

        var result = runner.run(diffFile, repoRoot);

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().title()).contains("SQL injection");
        assertThat(result.findings().getFirst().taskId()).isEqualTo("task-security");
    }

    private static final class StubLlmClient implements LlmClient {

        private final List<LlmResponse> scriptedResponses;

        private int cursor;

        private StubLlmClient(List<LlmResponse> scriptedResponses) {
            this.scriptedResponses = List.copyOf(scriptedResponses);
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            return scriptedResponses.get(cursor++);
        }

        @Override
        public Flux<LlmChunk> stream(LlmRequest request) {
            throw new UnsupportedOperationException("stream is not used in this test");
        }
    }
}
