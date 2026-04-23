package com.codepilot.cli;

import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.ToolCallInResponse;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.infrastructure.config.LlmProperties;
import com.codepilot.core.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CodePilotCli {

    private CodePilotCli() {
    }

    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            LlmClient llmClient = options.responseFile() == null
                    ? createLiveLlmClient(options)
                    : createScriptedLlmClient(options.responseFile());

            var reviewResult = new LocalReviewRunner(
                    llmClient,
                    options.model(),
                    options.provider() == null ? Map.of() : Map.of("provider", options.provider()),
                    6
            ).run(options.diffFile(), options.repoRoot());

            if (reviewResult.findings().isEmpty()) {
                System.out.println("No findings.");
                return;
            }

            for (Finding finding : reviewResult.findings()) {
                System.out.println("[%s] %s:%d %s".formatted(
                        finding.severity(),
                        finding.location().filePath(),
                        finding.location().startLine(),
                        finding.title()
                ));
                System.out.println(finding.description());
                if (!finding.suggestion().isBlank()) {
                    System.out.println("Suggestion: " + finding.suggestion());
                }
                if (!finding.evidence().isEmpty()) {
                    System.out.println("Evidence: " + String.join(" | ", finding.evidence()));
                }
                System.out.println();
            }
        } catch (RuntimeException error) {
            System.err.println("CodePilot CLI failed: " + error.getMessage());
            throw error;
        }
    }

    private static LlmClient createLiveLlmClient(CliOptions options) {
        String baseUrl = readRequiredEnv("CODEPILOT_LLM_BASE_URL");
        String apiKey = readRequiredEnv("CODEPILOT_LLM_API_KEY");

        LlmProperties.Provider provider = new LlmProperties.Provider();
        provider.setName(options.provider());
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(apiKey);
        provider.setModels(List.of(options.model()));

        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setDefaultProvider(provider.getName());
        llmProperties.setDefaultModel(options.model());
        llmProperties.setProviders(List.of(provider));

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        return new OpenAiCompatibleLlmClient(WebClient.builder(), objectMapper, llmProperties);
    }

    private static LlmClient createScriptedLlmClient(Path responseFile) {
        try {
            ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
            ScriptedResponse[] scriptedResponses = objectMapper.readValue(responseFile.toFile(), ScriptedResponse[].class);
            List<LlmResponse> responses = new ArrayList<>();
            for (ScriptedResponse scriptedResponse : scriptedResponses) {
                List<ToolCallInResponse> toolCalls = scriptedResponse.toolCalls() == null
                        ? List.of()
                        : scriptedResponse.toolCalls().stream()
                        .map(toolCall -> new ToolCallInResponse(toolCall.id(), toolCall.name(), toolCall.arguments()))
                        .toList();
                responses.add(new LlmResponse(
                        scriptedResponse.content() == null ? "" : scriptedResponse.content(),
                        toolCalls,
                        null,
                        scriptedResponse.finishReason() == null ? "stop" : scriptedResponse.finishReason()
                ));
            }
            return new ScriptedLlmClient(responses);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read scripted response file " + responseFile, error);
        }
    }

    private static String readRequiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }

    private record CliOptions(
            Path diffFile,
            Path repoRoot,
            String model,
            String provider,
            Path responseFile
    ) {

        private static CliOptions parse(String[] args) {
            if (args == null || args.length < 3 || !"review".equalsIgnoreCase(args[0])) {
                throw new IllegalArgumentException("Usage: review --diff <path> [--repo <path>] [--model <name>] [--provider <name>] [--response-file <path>]");
            }

            Path diffFile = null;
            Path repoRoot = Path.of(".").toAbsolutePath().normalize();
            String model = "codepilot-cli-review";
            String provider = "cli";
            Path responseFile = null;

            for (int index = 1; index < args.length; index++) {
                String arg = args[index];
                if ("--diff".equals(arg) && index + 1 < args.length) {
                    diffFile = Path.of(args[++index]).toAbsolutePath().normalize();
                } else if ("--repo".equals(arg) && index + 1 < args.length) {
                    repoRoot = Path.of(args[++index]).toAbsolutePath().normalize();
                } else if ("--model".equals(arg) && index + 1 < args.length) {
                    model = args[++index];
                } else if ("--provider".equals(arg) && index + 1 < args.length) {
                    provider = args[++index];
                } else if ("--response-file".equals(arg) && index + 1 < args.length) {
                    responseFile = Path.of(args[++index]).toAbsolutePath().normalize();
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument " + arg);
                }
            }

            if (diffFile == null) {
                throw new IllegalArgumentException("--diff is required");
            }
            return new CliOptions(diffFile, repoRoot, model, provider, responseFile);
        }
    }

    private record ScriptedResponse(
            String content,
            List<ScriptedToolCall> toolCalls,
            String finishReason
    ) {
    }

    private record ScriptedToolCall(
            String id,
            String name,
            Map<String, Object> arguments
    ) {
    }

    private static final class ScriptedLlmClient implements LlmClient {

        private final List<LlmResponse> responses;

        private int cursor;

        private ScriptedLlmClient(List<LlmResponse> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            if (cursor >= responses.size()) {
                throw new IllegalStateException("Scripted LLM ran out of responses");
            }
            return responses.get(cursor++);
        }

        @Override
        public Flux<LlmChunk> stream(LlmRequest request) {
            throw new UnsupportedOperationException("stream is not supported in CLI scripted mode");
        }
    }
}
