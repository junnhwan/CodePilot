package com.codepilot.core.infrastructure.llm;

import com.codepilot.core.domain.llm.LlmChunk;
import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.domain.llm.LlmClientException;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.LlmRequest;
import com.codepilot.core.domain.llm.LlmResponse;
import com.codepilot.core.domain.llm.LlmUsage;
import com.codepilot.core.domain.llm.ToolCallInResponse;
import com.codepilot.core.domain.llm.ToolDefinition;
import com.codepilot.core.infrastructure.config.LlmProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WebClient.Builder webClientBuilder;

    private final ObjectMapper objectMapper;

    private final LlmProperties llmProperties;

    public OpenAiCompatibleLlmClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            LlmProperties llmProperties
    ) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.llmProperties = llmProperties;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        var provider = resolveProvider(request);
        var root = buildClient(provider)
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildPayload(request, provider, false))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .onErrorMap(error -> new LlmClientException("LLM chat call failed for provider=" + provider.getName(), error))
                .block(provider.getReadTimeout());

        if (root == null) {
            throw new LlmClientException("LLM chat call returned no response for provider=" + provider.getName());
        }

        return toResponse(root);
    }

    @Override
    public Flux<LlmChunk> stream(LlmRequest request) {
        var provider = resolveProvider(request);
        return buildClient(provider)
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(buildPayload(request, provider, true))
                .retrieve()
                .bodyToFlux(String.class)
                .flatMapIterable(this::splitSsePayload)
                .map(String::trim)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .filter(line -> !line.isBlank())
                .takeWhile(line -> !Objects.equals("[DONE]", line))
                .map(this::toChunk)
                .onErrorMap(error -> new LlmClientException("LLM stream call failed for provider=" + provider.getName(), error));
    }

    private WebClient buildClient(LlmProperties.Provider provider) {
        var httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) provider.getConnectTimeout().toMillis())
                .responseTimeout(provider.getReadTimeout());

        var builder = webClientBuilder.clone()
                .baseUrl(stripTrailingSlash(provider.getBaseUrl()))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey());

        return builder.build();
    }

    private Map<String, Object> buildPayload(LlmRequest request, LlmProperties.Provider provider, boolean stream) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", resolveModel(request, provider));
        payload.put("messages", request.messages().stream().map(this::toMessagePayload).toList());
        payload.put("stream", stream);

        if (!request.tools().isEmpty()) {
            payload.put("tools", request.tools().stream().map(this::toToolPayload).toList());
        }

        request.params().forEach((key, value) -> {
            if (!"provider".equals(key)) {
                payload.put(key, value);
            }
        });
        return payload;
    }

    private Map<String, Object> toMessagePayload(LlmMessage message) {
        return Map.of(
                "role", message.role(),
                "content", message.content()
        );
    }

    private Map<String, Object> toToolPayload(ToolDefinition toolDefinition) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", toolDefinition.name(),
                        "description", toolDefinition.description(),
                        "parameters", toolDefinition.parameters()
                )
        );
    }

    private LlmResponse toResponse(JsonNode root) {
        var choices = root.path("choices");
        var choice = choices.isArray() && !choices.isEmpty() ? choices.get(0) : objectMapper.createObjectNode();
        var message = choice.path("message");
        return new LlmResponse(
                message.path("content").asText(""),
                toToolCalls(message.path("tool_calls")),
                toUsage(root.path("usage")),
                choice.path("finish_reason").asText("")
        );
    }

    private LlmChunk toChunk(String payload) {
        try {
            var root = objectMapper.readTree(payload);
            var choices = root.path("choices");
            var choice = choices.isArray() && !choices.isEmpty() ? choices.get(0) : objectMapper.createObjectNode();
            var delta = choice.path("delta");
            return new LlmChunk(
                    delta.path("content").asText(""),
                    toToolCalls(delta.path("tool_calls")),
                    toUsage(root.path("usage")),
                    choice.path("finish_reason").asText("")
            );
        } catch (JsonProcessingException error) {
            throw new LlmClientException("Failed to parse stream chunk", error);
        }
    }

    private List<ToolCallInResponse> toToolCalls(JsonNode toolCallsNode) {
        if (!toolCallsNode.isArray()) {
            return List.of();
        }

        var toolCalls = new ArrayList<ToolCallInResponse>();
        for (JsonNode toolCallNode : toolCallsNode) {
            var functionNode = toolCallNode.path("function");
            toolCalls.add(new ToolCallInResponse(
                    toolCallNode.path("id").asText(""),
                    functionNode.path("name").asText(""),
                    parseArguments(functionNode.path("arguments").asText("{}"))
            ));
        }
        return toolCalls;
    }

    private Map<String, Object> parseArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return Map.of();
        }

        try {
            return objectMapper.readValue(rawArguments, MAP_TYPE);
        } catch (JsonProcessingException error) {
            return Map.of("raw", rawArguments);
        }
    }

    private LlmUsage toUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode()) {
            return null;
        }
        return new LlmUsage(
                usageNode.path("prompt_tokens").isNumber() ? usageNode.path("prompt_tokens").asInt() : null,
                usageNode.path("completion_tokens").isNumber() ? usageNode.path("completion_tokens").asInt() : null,
                usageNode.path("total_tokens").isNumber() ? usageNode.path("total_tokens").asInt() : null
        );
    }

    private List<String> splitSsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        return List.of(payload.split("\\R"));
    }

    private LlmProperties.Provider resolveProvider(LlmRequest request) {
        var requestedProvider = request.params().get("provider");
        if (requestedProvider instanceof String providerName && !providerName.isBlank()) {
            return llmProperties.resolveProvider(providerName);
        }
        return llmProperties.resolveDefaultProvider();
    }

    private String resolveModel(LlmRequest request, LlmProperties.Provider provider) {
        if (request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        if (llmProperties.getDefaultModel() != null && !llmProperties.getDefaultModel().isBlank()) {
            return llmProperties.getDefaultModel();
        }
        if (!provider.getModels().isEmpty()) {
            return provider.getModels().get(0);
        }
        throw new LlmClientException("No model configured for provider=" + provider.getName());
    }

    private String stripTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
