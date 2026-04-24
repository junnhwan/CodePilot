package com.codepilot.gateway.review;

import com.codepilot.core.application.context.DefaultContextCompiler;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.context.ImpactCalculator;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.llm.ToolCallInResponse;
import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.core.domain.session.SessionEvent;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.codepilot.core.infrastructure.persistence.inmemory.InMemoryReviewSessionRepository;
import com.codepilot.gateway.github.GitHubCommentWriter;
import com.codepilot.gateway.github.GitHubPullRequestClient;
import com.codepilot.gateway.github.GitHubPullRequestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubReviewWorkerTest {

    @Test
    void processesQueuedReviewEventThroughDoneStateAndWritesComments() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        ReviewSessionRepository repository = new InMemoryReviewSessionRepository();
        ReviewSseBroadcaster broadcaster = new ReviewSseBroadcaster();
        RedisStreamReviewEventBuffer eventBuffer = mock(RedisStreamReviewEventBuffer.class);
        GitHubPullRequestClient pullRequestClient = mock(GitHubPullRequestClient.class);
        GitHubCommentWriter commentWriter = mock(GitHubCommentWriter.class);
        ProjectMemoryRepository projectMemoryRepository = new ProjectMemoryRepository() {
            @Override
            public Optional<ProjectMemory> findByProjectId(String projectId) {
                return Optional.empty();
            }

            @Override
            public void save(ProjectMemory projectMemory) {
                throw new UnsupportedOperationException("save is not used in this test");
            }
        };

        GitHubPullRequestEvent event = new GitHubPullRequestEvent(
                "session-1",
                "acme/repo",
                "acme",
                "repo",
                42,
                "https://github.com/acme/repo/pull/42",
                "head-sha",
                "base-sha"
        );
        repository.save(ReviewSession.initialize(
                "session-1",
                "acme/repo",
                42,
                "https://github.com/acme/repo/pull/42",
                Instant.parse("2026-04-23T09:55:00Z")
        ));

        when(eventBuffer.readPending(10)).thenReturn(List.of(new RedisStreamReviewEventBuffer.BufferedReviewEvent("171-0", event)));
        when(pullRequestClient.fetchPullRequestDiff("acme", "repo", 42)).thenReturn("""
                diff --git a/src/main/java/com/example/UserRepository.java b/src/main/java/com/example/UserRepository.java
                @@ -1,1 +1,6 @@
                +package com.example;
                +
                +class UserRepository {
                +  String findByName(String name) {
                +    return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", String.class);
                +  }
                +}
                """);
        when(pullRequestClient.fetchPullRequestFiles("acme", "repo", 42, "head-sha"))
                .thenReturn(List.of(new GitHubPullRequestClient.PullRequestFileSnapshot(
                        "src/main/java/com/example/UserRepository.java",
                        "modified",
                        """
                        package com.example;

                        class UserRepository {
                            String findByName(String name) {
                                return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", String.class);
                            }
                        }
                        """
                )));

        GitHubReviewWorker worker = new GitHubReviewWorker(
                eventBuffer,
                repository,
                pullRequestClient,
                commentWriter,
                broadcaster,
                new StubLlmClient(List.of(
                        new LlmResponse(
                                "",
                                List.of(new ToolCallInResponse(
                                        "call-1",
                                        "read_file",
                                        Map.of("file_path", "src/main/java/com/example/UserRepository.java")
                                )),
                                new LlmUsage(100, 12, 112),
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
                                      "confidence": 0.97,
                                      "category": "security",
                                      "title": "Potential SQL injection risk",
                                      "description": "User input is concatenated directly into SQL.",
                                      "suggestion": "Use a parameterized query.",
                                      "evidence": ["The query interpolates name."]
                                    }
                                  ]
                                }
                                """,
                                List.of(),
                                new LlmUsage(140, 80, 220),
                                "stop"
                        )
                )),
                projectMemoryRepository,
                new DiffAnalyzer(),
                new DefaultContextCompiler(
                        new DiffAnalyzer(),
                        new JavaParserAstParser(),
                        new ImpactCalculator(),
                        new TokenCounter(),
                        new ClasspathCompilationStrategyLoader(objectMapper).load("java-springboot-maven")
                ),
                objectMapper,
                new TokenCounter(),
                "mock-review-model",
                Map.of(),
                4
        );

        worker.processPendingEvents();

        ReviewSession stored = repository.findById("session-1").orElseThrow();
        assertThat(stored.state()).isEqualTo(AgentState.DONE);
        assertThat(stored.reviewResult()).isNotNull();
        assertThat(stored.reviewResult().findings()).hasSize(1);
        assertThat(repository.findEvents("session-1"))
                .extracting(SessionEvent::type)
                .contains(SessionEvent.Type.TASK_STARTED, SessionEvent.Type.FINDING_REPORTED, SessionEvent.Type.TASK_COMPLETED);
        verify(commentWriter).writeReview(any(), any());
        verify(eventBuffer).remove("171-0");
    }

    private static final class StubLlmClient implements LlmClient {

        private final List<LlmResponse> responses;
        private int cursor;

        private StubLlmClient(List<LlmResponse> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            return responses.get(cursor++);
        }

        @Override
        public Flux<LlmChunk> stream(LlmRequest request) {
            throw new UnsupportedOperationException("stream is not used in this test");
        }
    }
}
