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
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.plan.TaskGraph;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.application.plan.PlanningAgent;
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
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        RecordingProjectMemoryRepository projectMemoryRepository = new RecordingProjectMemoryRepository(
                ProjectMemory.empty("acme/repo")
                        .addPattern(new ReviewPattern(
                                "pattern-1",
                                "acme/repo",
                                ReviewPattern.PatternType.SECURITY_PATTERN,
                                "Validation missing before repository call",
                                "Controllers in this project often skip validation before DAO access.",
                                "repository.findById(request.userId())",
                                3,
                                Instant.parse("2026-04-24T00:00:00Z")
                        ))
                        .addConvention(new TeamConvention(
                                "conv-1",
                                "acme/repo",
                                TeamConvention.Category.SECURITY,
                                "Controllers must validate request input before repository access.",
                                "validator.check(request); repository.findById(request.userId());",
                                "repository.findById(request.userId()) without validation",
                                0.96d,
                                TeamConvention.Source.MANUAL
                        ))
                        .addConvention(new TeamConvention(
                                "conv-2",
                                "acme/repo",
                                TeamConvention.Category.FORMAT,
                                "Use Slf4j instead of direct System.out printing.",
                                "log.info(\"created\")",
                                "System.out.println(created)",
                                0.70d,
                                TeamConvention.Source.MANUAL
                        ))
        );

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
        assertThat(projectMemoryRepository.load("acme/repo").reviewPatterns())
                .extracting(ReviewPattern::title)
                .contains("Potential SQL injection risk");
        assertThat(projectMemoryRepository.saveCount()).isEqualTo(1);
    }

    @Test
    void keepsReviewDoneWhenDreamPersistenceFails() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        ReviewSessionRepository repository = new InMemoryReviewSessionRepository();
        ReviewSseBroadcaster broadcaster = new ReviewSseBroadcaster();
        RedisStreamReviewEventBuffer eventBuffer = mock(RedisStreamReviewEventBuffer.class);
        GitHubPullRequestClient pullRequestClient = mock(GitHubPullRequestClient.class);
        GitHubCommentWriter commentWriter = mock(GitHubCommentWriter.class);
        RecordingProjectMemoryRepository projectMemoryRepository = new RecordingProjectMemoryRepository(
                ProjectMemory.empty("acme/repo")
        );
        projectMemoryRepository.failOnSave(new IllegalStateException("storage offline"));

        GitHubPullRequestEvent event = new GitHubPullRequestEvent(
                "session-dream-fail",
                "acme/repo",
                "acme",
                "repo",
                99,
                "https://github.com/acme/repo/pull/99",
                "head-sha",
                "base-sha"
        );
        repository.save(ReviewSession.initialize(
                "session-dream-fail",
                "acme/repo",
                99,
                "https://github.com/acme/repo/pull/99",
                Instant.parse("2026-04-24T10:00:00Z")
        ));

        when(eventBuffer.readPending(10)).thenReturn(List.of(new RedisStreamReviewEventBuffer.BufferedReviewEvent("191-0", event)));
        when(pullRequestClient.fetchPullRequestDiff("acme", "repo", 99)).thenReturn("""
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
        when(pullRequestClient.fetchPullRequestFiles("acme", "repo", 99, "head-sha"))
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
                new FindingOnlyLlmClient(),
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

        ReviewSession stored = repository.findById("session-dream-fail").orElseThrow();
        assertThat(stored.state()).isEqualTo(AgentState.DONE);
        assertThat(projectMemoryRepository.saveCount()).isEqualTo(1);
        verify(commentWriter).writeReview(any(), any());
        verify(eventBuffer).remove("191-0");
    }

    @Test
    void resumesReviewingSessionAndOnlyExecutesRemainingTasks() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        ReviewSessionRepository repository = new InMemoryReviewSessionRepository();
        ReviewSseBroadcaster broadcaster = new ReviewSseBroadcaster();
        RedisStreamReviewEventBuffer eventBuffer = mock(RedisStreamReviewEventBuffer.class);
        GitHubPullRequestClient pullRequestClient = mock(GitHubPullRequestClient.class);
        GitHubCommentWriter commentWriter = mock(GitHubCommentWriter.class);
        RecordingProjectMemoryRepository projectMemoryRepository = new RecordingProjectMemoryRepository(
                ProjectMemory.empty("acme/repo")
        );

        GitHubPullRequestEvent event = new GitHubPullRequestEvent(
                "session-resume",
                "acme/repo",
                "acme",
                "repo",
                42,
                "https://github.com/acme/repo/pull/42",
                "head-sha",
                "base-sha"
        );

        String rawDiff = """
                diff --git a/src/main/java/com/example/UserRepository.java b/src/main/java/com/example/UserRepository.java
                @@ -1,1 +1,6 @@
                +package com.example;
                +
                +class UserRepository {
                +  String findByName(String name) {
                +    return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", String.class);
                +  }
                +}
                """;
        ReviewPlan reviewPlan = new PlanningAgent(new DiffAnalyzer()).plan("session-resume", rawDiff);
        ReviewTask securityTask = reviewPlan.taskGraph().allTasks().stream()
                .filter(task -> task.type() == ReviewTask.TaskType.SECURITY)
                .findFirst()
                .orElseThrow();

        repository.save(ReviewSession.initialize(
                        "session-resume",
                        "acme/repo",
                        42,
                        "https://github.com/acme/repo/pull/42",
                        Instant.parse("2026-04-24T11:00:00Z")
                )
                .startPlanning(reviewPlan.diffSummary(), Instant.parse("2026-04-24T11:01:00Z"))
                .attachPlan(reviewPlan, Instant.parse("2026-04-24T11:02:00Z"))
                .startReviewing(Instant.parse("2026-04-24T11:03:00Z")));
        repository.append(SessionEvent.of(
                "session-resume",
                SessionEvent.Type.TASK_STARTED,
                Instant.parse("2026-04-24T11:04:00Z"),
                Map.of(
                        "taskId", securityTask.taskId(),
                        "type", securityTask.type().name(),
                        "files", securityTask.targetFiles()
                )
        ));
        repository.append(SessionEvent.of(
                "session-resume",
                SessionEvent.Type.FINDING_REPORTED,
                Instant.parse("2026-04-24T11:04:10Z"),
                Map.ofEntries(
                        Map.entry("findingId", "finding-resumed-security"),
                        Map.entry("taskId", securityTask.taskId()),
                        Map.entry("category", "security"),
                        Map.entry("severity", "HIGH"),
                        Map.entry("confidence", 0.99d),
                        Map.entry("file", "src/main/java/com/example/UserRepository.java"),
                        Map.entry("startLine", 4),
                        Map.entry("endLine", 4),
                        Map.entry("title", "Potential SQL injection risk"),
                        Map.entry("description", "User input is concatenated directly into SQL."),
                        Map.entry("suggestion", "Use a parameterized query."),
                        Map.entry("evidence", List.of("The query interpolates request data into SQL."))
                )
        ));
        repository.append(SessionEvent.of(
                "session-resume",
                SessionEvent.Type.TASK_COMPLETED,
                Instant.parse("2026-04-24T11:04:20Z"),
                Map.of(
                        "taskId", securityTask.taskId(),
                        "type", securityTask.type().name(),
                        "findingCount", 1,
                        "partial", false
                )
        ));

        when(eventBuffer.readPending(10)).thenReturn(List.of(new RedisStreamReviewEventBuffer.BufferedReviewEvent("181-0", event)));
        when(pullRequestClient.fetchPullRequestDiff("acme", "repo", 42)).thenReturn(rawDiff);
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

        RemainingTaskLlmClient llmClient = new RemainingTaskLlmClient();
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

        ReviewSession stored = repository.findById("session-resume").orElseThrow();
        assertThat(stored.state()).isEqualTo(AgentState.DONE);
        assertThat(stored.reviewResult()).isNotNull();
        assertThat(stored.reviewResult().findings())
                .extracting(finding -> finding.title())
                .containsExactly("Potential SQL injection risk");
        assertThat(llmClient.seenTaskTypes())
                .containsExactlyInAnyOrder(
                        ReviewTask.TaskType.PERF,
                        ReviewTask.TaskType.STYLE,
                        ReviewTask.TaskType.MAINTAIN
                );
        verify(commentWriter).writeReview(any(), any());
        verify(eventBuffer).remove("181-0");
    }

    @Test
    void resumesReportingSessionFromEventsWithoutReRunningReviewersOrMerge() {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        ReviewSessionRepository repository = new InMemoryReviewSessionRepository();
        ReviewSseBroadcaster broadcaster = new ReviewSseBroadcaster();
        RedisStreamReviewEventBuffer eventBuffer = mock(RedisStreamReviewEventBuffer.class);
        GitHubPullRequestClient pullRequestClient = mock(GitHubPullRequestClient.class);
        GitHubCommentWriter commentWriter = mock(GitHubCommentWriter.class);
        RecordingProjectMemoryRepository projectMemoryRepository = new RecordingProjectMemoryRepository(
                ProjectMemory.empty("acme/repo")
        );

        GitHubPullRequestEvent event = new GitHubPullRequestEvent(
                "session-reporting-resume",
                "acme/repo",
                "acme",
                "repo",
                77,
                "https://github.com/acme/repo/pull/77",
                "head-sha",
                "base-sha"
        );
        ReviewPlan reviewPlan = singleTaskPlan("session-reporting-resume");
        ReviewTask securityTask = reviewPlan.taskGraph().allTasks().getFirst();
        ReviewResult mergedResult = mergedResult("session-reporting-resume", securityTask.taskId());

        ReviewSession reportingSession = ReviewSession.initialize(
                        "session-reporting-resume",
                        "acme/repo",
                        77,
                        "https://github.com/acme/repo/pull/77",
                        Instant.parse("2026-04-24T12:00:00Z")
                )
                .startPlanning(reviewPlan.diffSummary(), Instant.parse("2026-04-24T12:01:00Z"))
                .attachPlan(reviewPlan, Instant.parse("2026-04-24T12:02:00Z"))
                .startReviewing(Instant.parse("2026-04-24T12:03:00Z"))
                .startMerging(Instant.parse("2026-04-24T12:05:00Z"))
                .startReporting(mergedResult, Instant.parse("2026-04-24T12:06:00Z"));
        reportingSession.events().forEach(repository::append);
        appendCompletedSecurityTaskEvents(
                repository,
                "session-reporting-resume",
                securityTask,
                Instant.parse("2026-04-24T12:04:00Z")
        );

        when(eventBuffer.readPending(10)).thenReturn(List.of(new RedisStreamReviewEventBuffer.BufferedReviewEvent("221-0", event)));

        GitHubReviewWorker worker = new GitHubReviewWorker(
                eventBuffer,
                repository,
                pullRequestClient,
                commentWriter,
                broadcaster,
                new NeverCalledLlmClient(),
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

        ArgumentCaptor<ReviewResult> reviewResultCaptor = ArgumentCaptor.forClass(ReviewResult.class);
        verify(commentWriter).writeReview(any(), reviewResultCaptor.capture());
        verify(eventBuffer).remove("221-0");
        verifyNoInteractions(pullRequestClient);

        ReviewSession stored = repository.findById("session-reporting-resume").orElseThrow();
        assertThat(stored.state()).isEqualTo(AgentState.DONE);
        assertThat(reviewResultCaptor.getValue().generatedAt()).isEqualTo(mergedResult.generatedAt());
        assertThat(stored.reviewResult().generatedAt()).isEqualTo(mergedResult.generatedAt());
    }

    private ReviewPlan singleTaskPlan(String sessionId) {
        ReviewTask securityTask = ReviewTask.pending(
                sessionId + "-security",
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                List.of("src/main/java/com/example/UserRepository.java"),
                List.of("restore reporting without re-running merge"),
                List.of()
        );
        DiffSummary diffSummary = DiffSummary.of(List.of(new DiffSummary.ChangedFile(
                "src/main/java/com/example/UserRepository.java",
                DiffSummary.ChangeType.MODIFIED,
                6,
                1,
                List.of("UserRepository#findByName")
        )));
        return new ReviewPlan(
                "plan-" + sessionId,
                sessionId,
                diffSummary,
                TaskGraph.of(List.of(securityTask)),
                ReviewPlan.ReviewStrategy.SECURITY_FIRST
        );
    }

    private ReviewResult mergedResult(String sessionId, String taskId) {
        return new ReviewResult(
                sessionId,
                List.of(Finding.reported(
                        "finding-" + taskId,
                        taskId,
                        "security",
                        com.codepilot.core.domain.review.Severity.HIGH,
                        0.97d,
                        new Finding.CodeLocation("src/main/java/com/example/UserRepository.java", 4, 4),
                        "Potential SQL injection risk",
                        "User input is concatenated directly into SQL.",
                        "Use a parameterized query.",
                        List.of("The query interpolates name.")
                )),
                false,
                Instant.parse("2026-04-24T12:05:30Z")
        );
    }

    private void appendCompletedSecurityTaskEvents(
            ReviewSessionRepository repository,
            String sessionId,
            ReviewTask securityTask,
            Instant startedAt
    ) {
        repository.append(SessionEvent.of(
                sessionId,
                SessionEvent.Type.TASK_STARTED,
                startedAt,
                Map.of(
                        "taskId", securityTask.taskId(),
                        "type", securityTask.type().name(),
                        "files", securityTask.targetFiles()
                )
        ));
        repository.append(SessionEvent.of(
                sessionId,
                SessionEvent.Type.FINDING_REPORTED,
                startedAt.plusSeconds(10),
                Map.ofEntries(
                        Map.entry("findingId", "finding-" + securityTask.taskId()),
                        Map.entry("taskId", securityTask.taskId()),
                        Map.entry("category", "security"),
                        Map.entry("severity", "HIGH"),
                        Map.entry("confidence", 0.97d),
                        Map.entry("file", "src/main/java/com/example/UserRepository.java"),
                        Map.entry("startLine", 4),
                        Map.entry("endLine", 4),
                        Map.entry("title", "Potential SQL injection risk"),
                        Map.entry("description", "User input is concatenated directly into SQL."),
                        Map.entry("suggestion", "Use a parameterized query."),
                        Map.entry("evidence", List.of("The query interpolates name."))
                )
        ));
        repository.append(SessionEvent.of(
                sessionId,
                SessionEvent.Type.TASK_COMPLETED,
                startedAt.plusSeconds(20),
                Map.of(
                        "taskId", securityTask.taskId(),
                        "type", securityTask.type().name(),
                        "findingCount", 1,
                        "partial", false
                )
        ));
    }

    private static final class TaskAwareLlmClient implements LlmClient {

        private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

        private final List<ReviewTask.TaskType> seenTaskTypes = new CopyOnWriteArrayList<>();

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

    private static final class RemainingTaskLlmClient implements LlmClient {

        private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

        private final List<ReviewTask.TaskType> seenTaskTypes = new CopyOnWriteArrayList<>();

        @Override
        public LlmResponse chat(LlmRequest request) {
            ReviewTask.TaskType taskType = taskType(request.messages());
            seenTaskTypes.add(taskType);
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

    private static final class FindingOnlyLlmClient implements LlmClient {

        private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

        @Override
        public LlmResponse chat(LlmRequest request) {
            ReviewTask.TaskType taskType = taskType(request.messages());
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
    }

    private static final class NeverCalledLlmClient implements LlmClient {

        @Override
        public LlmResponse chat(LlmRequest request) {
            throw new IllegalStateException("LLM should not be called while resuming REPORTING");
        }

        @Override
        public Flux<LlmChunk> stream(LlmRequest request) {
            throw new UnsupportedOperationException("stream is not used in this test");
        }
    }

    private static final class RecordingProjectMemoryRepository implements ProjectMemoryRepository {

        private ProjectMemory projectMemory;

        private RuntimeException saveFailure;

        private int saveCount;

        private RecordingProjectMemoryRepository(ProjectMemory projectMemory) {
            this.projectMemory = projectMemory;
        }

        @Override
        public Optional<ProjectMemory> findByProjectId(String projectId) {
            if (projectMemory == null || !projectMemory.projectId().equals(projectId)) {
                return Optional.empty();
            }
            return Optional.of(projectMemory);
        }

        @Override
        public void save(ProjectMemory projectMemory) {
            saveCount++;
            if (saveFailure != null) {
                throw saveFailure;
            }
            this.projectMemory = projectMemory;
        }

        private void failOnSave(RuntimeException saveFailure) {
            this.saveFailure = saveFailure;
        }

        private int saveCount() {
            return saveCount;
        }
    }
}
