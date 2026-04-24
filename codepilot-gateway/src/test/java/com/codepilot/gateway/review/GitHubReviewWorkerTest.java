package com.codepilot.gateway.review;

import com.codepilot.core.application.context.DefaultContextCompiler;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.context.ImpactCalculator;
import com.codepilot.core.application.memory.MemoryService;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import com.codepilot.core.domain.plan.ReviewTask;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                return Optional.of(ProjectMemory.empty(projectId)
                        .addPattern(new ReviewPattern(
                                "pattern-1",
                                projectId,
                                ReviewPattern.PatternType.SECURITY_PATTERN,
                                "Validation missing before repository call",
                                "Controllers in this project often skip validation before DAO access.",
                                "repository.findById(request.userId())",
                                3,
                                Instant.parse("2026-04-24T00:00:00Z")
                        ))
                        .addConvention(new TeamConvention(
                                "conv-1",
                                projectId,
                                TeamConvention.Category.SECURITY,
                                "Controllers must validate request input before repository access.",
                                "validator.check(request); repository.findById(request.userId());",
                                "repository.findById(request.userId()) without validation",
                                0.96d,
                                TeamConvention.Source.MANUAL
                        ))
                        .addConvention(new TeamConvention(
                                "conv-2",
                                projectId,
                                TeamConvention.Category.FORMAT,
                                "Use Slf4j instead of direct System.out printing.",
                                "log.info(\"created\")",
                                "System.out.println(created)",
                                0.70d,
                                TeamConvention.Source.MANUAL
                        )));
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

        TaskAwareLlmClient llmClient = new TaskAwareLlmClient();
        GitHubReviewWorker worker = new GitHubReviewWorker(
                eventBuffer,
                repository,
                pullRequestClient,
                commentWriter,
                broadcaster,
                llmClient,
                projectMemoryRepository,
                new DiffAnalyzer(),
                new DefaultContextCompiler(
                        new DiffAnalyzer(),
                        new JavaParserAstParser(),
                        new ImpactCalculator(),
                        new TokenCounter(),
                        new ClasspathCompilationStrategyLoader(objectMapper).load("java-springboot-maven"),
                        new MemoryService(new TokenCounter())
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
        assertThat(stored.reviewPlan()).isNotNull();
        assertThat(stored.reviewPlan().taskGraph().allTasks()).hasSize(4);
        assertThat(stored.reviewResult()).isNotNull();
        assertThat(stored.reviewResult().findings()).hasSize(1);
        assertThat(llmClient.seenTaskTypes())
                .contains(
                        ReviewTask.TaskType.SECURITY,
                        ReviewTask.TaskType.PERF,
                        ReviewTask.TaskType.STYLE,
                        ReviewTask.TaskType.MAINTAIN
                );
        assertThat(repository.findEvents("session-1"))
                .extracting(SessionEvent::type)
                .contains(SessionEvent.Type.TASK_STARTED, SessionEvent.Type.FINDING_REPORTED, SessionEvent.Type.TASK_COMPLETED);
        assertThat(repository.findEvents("session-1").stream()
                .filter(eventEntry -> eventEntry.type() == SessionEvent.Type.TASK_STARTED))
                .hasSize(4);
        assertThat(repository.findEvents("session-1").stream()
                .filter(eventEntry -> eventEntry.type() == SessionEvent.Type.TASK_COMPLETED))
                .hasSize(4);
        verify(commentWriter).writeReview(any(), any());
        verify(eventBuffer).remove("171-0");
    }

    private static final class TaskAwareLlmClient implements LlmClient {

        private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

        private final List<ReviewTask.TaskType> seenTaskTypes = new ArrayList<>();

        public LlmResponse chat(LlmRequest request) {
            ReviewTask.TaskType taskType = taskType(request.messages());
            seenTaskTypes.add(taskType);
            if (taskType == ReviewTask.TaskType.SECURITY) {
                String systemPrompt = request.messages().stream()
                        .filter(message -> "system".equals(message.role()))
                        .map(LlmMessage::content)
                        .findFirst()
                        .orElseThrow();
                assertThat(systemPrompt).contains("Validation missing before repository call");
                assertThat(systemPrompt).contains("Controllers must validate request input before repository access.");
                assertThat(systemPrompt).doesNotContain("Use Slf4j instead of direct System.out printing.");
            }
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
                );
            }
            return new LlmResponse("""
                    {
                      "decision": "DELIVER",
                      "findings": []
                    }
                    """, List.of(), new LlmUsage(90, 20, 110), "stop");
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
