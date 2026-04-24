package com.codepilot.mcp.testsupport;

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
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.codepilot.mcp.CodePilotMcpServerFactory;
import com.codepilot.mcp.review.GitHubPullRequestClient;
import com.codepilot.mcp.review.McpReviewService;
import com.codepilot.mcp.tool.ReviewDiffToolHandler;
import com.codepilot.mcp.tool.ReviewPrToolHandler;
import com.codepilot.mcp.tool.SearchMemoryToolHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodePilotMcpServerTestMain {

    private CodePilotMcpServerTestMain() {
    }

    public static void main(String[] args) throws InterruptedException {
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
        ProjectMemoryRepository projectMemoryRepository = new SeededProjectMemoryRepository();
        McpReviewService reviewService = new McpReviewService(
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

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(jsonMapper);
        List<McpServerFeatures.SyncToolSpecification> tools = List.of(
                new ReviewDiffToolHandler(reviewService).specification(),
                new ReviewPrToolHandler(reviewService, new GitHubPullRequestClient(RestClient.builder())).specification(),
                new SearchMemoryToolHandler(projectMemoryRepository).specification()
        );
        McpSyncServer server = new CodePilotMcpServerFactory(jsonMapper)
                .create(transportProvider, tools);
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        new CountDownLatch(1).await();
    }

    private static final class SeededProjectMemoryRepository implements ProjectMemoryRepository {

        @Override
        public Optional<ProjectMemory> findByProjectId(String projectId) {
            return Optional.of(ProjectMemory.empty(projectId)
                    .addPattern(new ReviewPattern(
                            "pattern-1",
                            projectId,
                            ReviewPattern.PatternType.SECURITY_PATTERN,
                            "Validation missing before repository call",
                            "Controllers in this project often skip validation before DAO access.",
                            "repository.findById(request.id())",
                            3,
                            Instant.parse("2026-04-23T00:00:00Z")
                    ))
                    .addConvention(new TeamConvention(
                            "conv-1",
                            projectId,
                            TeamConvention.Category.ARCHITECTURE,
                            "Gateway should stay thin and delegate validation into core.",
                            "Gateway delegates into use case after request validation.",
                            "Gateway writes domain state directly.",
                            0.9d,
                            TeamConvention.Source.MANUAL
                    )));
        }

        @Override
        public void save(ProjectMemory projectMemory) {
            throw new UnsupportedOperationException("save is not used in test MCP server");
        }
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
