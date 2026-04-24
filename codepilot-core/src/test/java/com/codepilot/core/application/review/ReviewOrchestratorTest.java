package com.codepilot.core.application.review;

import com.codepilot.core.application.context.DefaultContextCompiler;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.context.ImpactCalculator;
import com.codepilot.core.application.memory.MemoryService;
import com.codepilot.core.application.plan.PlanningAgent;
import com.codepilot.core.application.tool.ToolExecutor;
import com.codepilot.core.application.tool.ToolRegistry;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.context.ImpactSet;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewOrchestratorTest {

    @TempDir
    Path repoRoot;

    @Test
    void executesIndependentTasksInParallelAndRunsDependentTasksInLaterWave() throws IOException {
        Path authFile = repoRoot.resolve("src/main/java/com/example/auth/AuthController.java");
        Files.createDirectories(authFile.getParent());
        Files.writeString(authFile, """
                package com.example.auth;

                class AuthController {
                    LoginResponse login(LoginRequest request) {
                        return authService.login(request.username(), request.password(), request.token());
                    }
                }
                """);

        Path repositoryFile = repoRoot.resolve("src/main/java/com/example/repository/UserRepository.java");
        Files.createDirectories(repositoryFile.getParent());
        Files.writeString(repositoryFile, """
                package com.example.repository;

                class UserRepository {
                    UserEntity findByName(String name) {
                        return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", rowMapper);
                    }
                }
                """);

        String rawDiff = """
                diff --git a/src/main/java/com/example/auth/AuthController.java b/src/main/java/com/example/auth/AuthController.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/auth/AuthController.java
                +++ b/src/main/java/com/example/auth/AuthController.java
                @@ -2,3 +2,7 @@
                +class AuthController {
                +    LoginResponse login(LoginRequest request) {
                +        return authService.login(request.username(), request.password(), request.token());
                +    }
                +}
                diff --git a/src/main/java/com/example/repository/UserRepository.java b/src/main/java/com/example/repository/UserRepository.java
                index 3333333..4444444 100644
                --- a/src/main/java/com/example/repository/UserRepository.java
                +++ b/src/main/java/com/example/repository/UserRepository.java
                @@ -2,3 +2,7 @@
                +class UserRepository {
                +    UserEntity findByName(String name) {
                +        return jdbcTemplate.queryForObject("select * from users where name = '" + name + "'", rowMapper);
                +    }
                +}
                """;

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        TokenCounter tokenCounter = new TokenCounter();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new ReadFileTool(repoRoot)));
        TaskAwareConcurrentLlmClient llmClient = new TaskAwareConcurrentLlmClient();
        Executor executor = Executors.newFixedThreadPool(4);

        ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                new PlanningAgent(new DiffAnalyzer()),
                new DefaultContextCompiler(
                        new DiffAnalyzer(),
                        new JavaParserAstParser(),
                        new ImpactCalculator(),
                        tokenCounter,
                        new ClasspathCompilationStrategyLoader(objectMapper).load("java-springboot-maven"),
                        new MemoryService(tokenCounter)
                ),
                new ReviewEngine(
                        llmClient,
                        toolRegistry,
                        new ToolExecutor(toolRegistry),
                        new ToolCallParser(objectMapper),
                        tokenCounter,
                        new ContextGovernor(tokenCounter),
                        new LoopDetector(),
                        "mock-review-model",
                        Map.of(),
                        3
                ),
                new ReviewerPool(),
                new MergeAgent(),
                executor
        );

        RecordingListener listener = new RecordingListener();
        ReviewOrchestrator.RunResult result = orchestrator.run(
                "session-orchestrator",
                repoRoot,
                rawDiff,
                ProjectMemory.empty("project-alpha"),
                Map.of("entrypoint", "test"),
                listener
        );

        assertThat(result.reviewPlan().taskGraph().allTasks()).hasSize(4);
        assertThat(listener.startedTypes().subList(0, 2))
                .containsExactlyInAnyOrder(ReviewTask.TaskType.SECURITY, ReviewTask.TaskType.PERF);
        assertThat(listener.dependencyViolations()).isEmpty();
        assertThat(listener.completedTypes()).hasSize(4);
        assertThat(llmClient.maxConcurrentCalls()).isGreaterThanOrEqualTo(2);
        assertThat(result.reviewResult().findings())
                .extracting(Finding::title)
                .containsExactly("SQL injection risk", "Repository call inside loop");
        assertThat(result.reviewResult().findings().getFirst().status()).isEqualTo(Finding.Status.REPORTED);
    }

    private static final class RecordingListener implements ReviewOrchestrator.Listener {

        private final List<ReviewTask.TaskType> startedTypes = new CopyOnWriteArrayList<>();
        private final List<ReviewTask.TaskType> completedTypes = new CopyOnWriteArrayList<>();
        private final List<String> dependencyViolations = new CopyOnWriteArrayList<>();
        private final AtomicInteger baseCompleted = new AtomicInteger();

        @Override
        public void onTaskStarted(ReviewTask reviewTask, ContextPack contextPack) {
            startedTypes.add(reviewTask.type());
            if ((reviewTask.type() == ReviewTask.TaskType.STYLE || reviewTask.type() == ReviewTask.TaskType.MAINTAIN)
                    && baseCompleted.get() < 2) {
                dependencyViolations.add(reviewTask.taskId());
            }
        }

        @Override
        public void onTaskCompleted(ReviewTask reviewTask, com.codepilot.core.domain.review.ReviewResult reviewResult) {
            completedTypes.add(reviewTask.type());
            if (reviewTask.type() == ReviewTask.TaskType.SECURITY || reviewTask.type() == ReviewTask.TaskType.PERF) {
                baseCompleted.incrementAndGet();
            }
        }

        private List<ReviewTask.TaskType> startedTypes() {
            return List.copyOf(startedTypes);
        }

        private List<ReviewTask.TaskType> completedTypes() {
            return List.copyOf(completedTypes);
        }

        private List<String> dependencyViolations() {
            return List.copyOf(dependencyViolations);
        }
    }

    private static final class TaskAwareConcurrentLlmClient implements LlmClient {

        private static final Pattern TASK_TYPE_PATTERN = Pattern.compile("(?m)^- type: ([A-Z]+)$");

        private final CountDownLatch firstWaveLatch = new CountDownLatch(2);
        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger maxConcurrentCalls = new AtomicInteger();

        @Override
        public LlmResponse chat(LlmRequest request) {
            ReviewTask.TaskType taskType = taskType(request);
            int active = activeCalls.incrementAndGet();
            maxConcurrentCalls.updateAndGet(current -> Math.max(current, active));
            try {
                if (taskType == ReviewTask.TaskType.SECURITY || taskType == ReviewTask.TaskType.PERF) {
                    firstWaveLatch.countDown();
                    firstWaveLatch.await(1, TimeUnit.SECONDS);
                    Thread.sleep(75);
                }
                return switch (taskType) {
                    case SECURITY -> deliver("""
                            {
                              "decision": "DELIVER",
                              "findings": [
                                {
                                  "file": "src/main/java/com/example/repository/UserRepository.java",
                                  "line": 5,
                                  "severity": "HIGH",
                                  "confidence": 0.96,
                                  "category": "security",
                                  "title": "SQL injection risk",
                                  "description": "User input is concatenated into SQL.",
                                  "suggestion": "Use a parameterized query.",
                                  "evidence": ["The query string contains '+ name +'."] 
                                }
                              ]
                            }
                            """);
                    case PERF -> deliver("""
                            {
                              "decision": "DELIVER",
                              "findings": [
                                {
                                  "file": "src/main/java/com/example/repository/UserRepository.java",
                                  "line": 5,
                                  "severity": "MEDIUM",
                                  "confidence": 0.88,
                                  "category": "perf",
                                  "title": "Repository call inside loop",
                                  "description": "Repeated repository work can turn into N+1 style access.",
                                  "suggestion": "Batch load the records before iterating.",
                                  "evidence": ["The diff introduces a repository lookup pattern that should be checked for loops."] 
                                }
                              ]
                            }
                            """);
                    case STYLE -> deliver("""
                            {
                              "decision": "DELIVER",
                              "findings": [
                                {
                                  "file": "src/main/java/com/example/auth/AuthController.java",
                                  "line": 4,
                                  "severity": "LOW",
                                  "confidence": 0.32,
                                  "category": "style",
                                  "title": "Variable naming could be clearer",
                                  "description": "The local name is generic.",
                                  "suggestion": "Use a more explicit variable name.",
                                  "evidence": []
                                }
                              ]
                            }
                            """);
                    case MAINTAIN -> deliver("""
                            {
                              "decision": "DELIVER",
                              "findings": [
                                {
                                  "file": "src/main/java/com/example/repository/UserRepository.java",
                                  "line": 5,
                                  "severity": "MEDIUM",
                                  "confidence": 0.70,
                                  "category": "maintain",
                                  "title": "SQL injection risk",
                                  "description": "String-built SQL is duplicated and hard to reason about.",
                                  "suggestion": "Extract a prepared statement helper.",
                                  "evidence": ["The same changed line assembles SQL from request data."] 
                                }
                              ]
                            }
                            """);
                };
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating LLM response", interruptedException);
            } finally {
                activeCalls.decrementAndGet();
            }
        }

        @Override
        public Flux<LlmChunk> stream(LlmRequest request) {
            throw new UnsupportedOperationException("stream is not used in this test");
        }

        private ReviewTask.TaskType taskType(LlmRequest request) {
            String systemPrompt = request.messages().stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(message -> message.content() == null ? "" : message.content())
                    .findFirst()
                    .orElseThrow();
            Matcher matcher = TASK_TYPE_PATTERN.matcher(systemPrompt);
            if (!matcher.find()) {
                throw new IllegalStateException("Missing task type in prompt: " + systemPrompt);
            }
            return ReviewTask.TaskType.valueOf(matcher.group(1));
        }

        private LlmResponse deliver(String content) {
            return new LlmResponse(content, List.of(), new LlmUsage(120, 80, 200), "stop");
        }

        private int maxConcurrentCalls() {
            return maxConcurrentCalls.get();
        }
    }
}
