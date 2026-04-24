package com.codepilot.mcp;

import com.codepilot.core.application.context.DefaultContextCompiler;
import com.codepilot.core.application.context.DiffAnalyzer;
import com.codepilot.core.application.context.ImpactCalculator;
import com.codepilot.core.application.memory.MemoryService;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.infrastructure.config.LlmProperties;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.codepilot.core.infrastructure.llm.OpenAiCompatibleLlmClient;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

public final class CodePilotMcpServerApplication {

    private CodePilotMcpServerApplication() {
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

        LlmClient llmClient = new OpenAiCompatibleLlmClient(
                WebClient.builder(),
                objectMapper,
                loadLlmProperties(System.getenv())
        );
        ProjectMemoryRepository projectMemoryRepository = new ProjectMemoryRepository() {
            @Override
            public Optional<ProjectMemory> findByProjectId(String projectId) {
                return Optional.of(ProjectMemory.empty(projectId));
            }

            @Override
            public void save(ProjectMemory projectMemory) {
                throw new UnsupportedOperationException("CodePilot MCP server does not persist project memory by default");
            }
        };

        McpReviewService reviewService = new McpReviewService(
                llmClient,
                projectMemoryRepository,
                diffAnalyzer,
                contextCompiler,
                objectMapper,
                tokenCounter,
                loadReviewModel(System.getenv()),
                Map.of(),
                6
        );
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        List<McpServerFeatures.SyncToolSpecification> tools = List.of(
                new ReviewDiffToolHandler(reviewService).specification(),
                new ReviewPrToolHandler(reviewService, new GitHubPullRequestClient(RestClient.builder())).specification(),
                new SearchMemoryToolHandler(projectMemoryRepository).specification()
        );
        McpSyncServer server = new CodePilotMcpServerFactory(jsonMapper).create(tools);
        Runtime.getRuntime().addShutdownHook(new Thread(server::closeGracefully));
        new CountDownLatch(1).await();
    }

    private static String loadReviewModel(Map<String, String> environment) {
        String configured = trimToNull(environment.get("CODEPILOT_LLM_DEFAULT_MODEL"));
        return configured == null ? "gpt-4.1-mini" : configured;
    }

    private static LlmProperties loadLlmProperties(Map<String, String> environment) {
        LlmProperties properties = new LlmProperties();
        String providerName = trimToNull(environment.get("CODEPILOT_LLM_DEFAULT_PROVIDER"));
        String defaultModel = loadReviewModel(environment);
        properties.setDefaultProvider(providerName == null ? "openai" : providerName);
        properties.setDefaultModel(defaultModel);

        LlmProperties.Provider provider = new LlmProperties.Provider();
        provider.setName(properties.getDefaultProvider());
        provider.setBaseUrl(firstNonBlank(
                environment.get("CODEPILOT_LLM_BASE_URL"),
                environment.get("OPENAI_BASE_URL"),
                "https://api.openai.com/v1"
        ));
        provider.setApiKey(firstNonBlank(
                environment.get("CODEPILOT_LLM_API_KEY"),
                environment.get("OPENAI_API_KEY"),
                ""
        ));
        provider.setModels(List.of(defaultModel));
        provider.setConnectTimeout(Duration.ofSeconds(10));
        provider.setReadTimeout(Duration.ofSeconds(60));
        properties.setProviders(List.of(provider));
        return properties;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        String candidate = trimToNull(first);
        if (candidate != null) {
            return candidate;
        }
        candidate = trimToNull(second);
        return candidate == null ? fallback : candidate;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
