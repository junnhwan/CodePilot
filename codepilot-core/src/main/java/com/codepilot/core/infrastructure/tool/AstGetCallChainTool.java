package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.context.AstParser;
import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AstGetCallChainTool implements Tool {

    private final Path repoRoot;

    private final ObjectMapper objectMapper;

    private final AstParser astParser;

    public AstGetCallChainTool(Path repoRoot, ObjectMapper objectMapper, AstParser astParser) {
        this.repoRoot = RepositoryToolSupport.normalizeRepoRoot(repoRoot);
        this.objectMapper = objectMapper;
        this.astParser = astParser;
    }

    @Override
    public String name() {
        return "ast_get_call_chain";
    }

    @Override
    public String description() {
        return "Resolve upstream or downstream Java method call chains from a method symbol.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "symbol", Map.of("type", "string"),
                        "direction", Map.of("type", "string"),
                        "max_depth", Map.of("type", "integer"),
                        "max_results", Map.of("type", "integer")
                ),
                "required", List.of("symbol")
        );
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public boolean exclusive() {
        return false;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        String symbol = String.valueOf(call.arguments().getOrDefault("symbol", "")).trim();
        if (symbol.isBlank()) {
            return ToolResult.failure(call.callId(), "symbol must not be blank", Map.of());
        }

        Direction direction = Direction.from(String.valueOf(call.arguments().getOrDefault("direction", "DOWNSTREAM")));
        int maxDepth = RepositoryToolSupport.positiveInt(call.arguments().get("max_depth"), 3);
        int maxResults = RepositoryToolSupport.positiveInt(call.arguments().get("max_results"), 10);

        List<String> callChains = JavaRepositoryAstIndex.build(repoRoot, astParser)
                .findCallChains(symbol, direction, maxDepth, maxResults);
        try {
            return ToolResult.success(
                    call.callId(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(callChains),
                    Map.of(
                            "symbol", symbol,
                            "direction", direction.name(),
                            "chainCount", callChains.size()
                    )
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize call chains for " + symbol, exception);
        }
    }

    public enum Direction {
        DOWNSTREAM,
        UPSTREAM,
        BOTH;

        static Direction from(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return DOWNSTREAM;
            }
            try {
                return Direction.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return DOWNSTREAM;
            }
        }
    }
}
