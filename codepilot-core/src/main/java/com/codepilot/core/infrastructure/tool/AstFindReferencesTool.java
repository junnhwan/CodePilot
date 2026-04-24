package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.context.AstParser;
import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class AstFindReferencesTool implements Tool {

    private final Path repoRoot;

    private final ObjectMapper objectMapper;

    private final AstParser astParser;

    public AstFindReferencesTool(Path repoRoot, ObjectMapper objectMapper, AstParser astParser) {
        this.repoRoot = RepositoryToolSupport.normalizeRepoRoot(repoRoot);
        this.objectMapper = objectMapper;
        this.astParser = astParser;
    }

    @Override
    public String name() {
        return "ast_find_references";
    }

    @Override
    public String description() {
        return "Find type or method references across Java source files in the repository.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "symbol", Map.of("type", "string"),
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

        int maxResults = RepositoryToolSupport.positiveInt(call.arguments().get("max_results"), 20);
        List<JavaRepositoryAstIndex.ReferenceHit> references = JavaRepositoryAstIndex.build(repoRoot, astParser)
                .findReferences(symbol, maxResults);
        try {
            return ToolResult.success(
                    call.callId(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(references),
                    Map.of("symbol", symbol, "matchCount", references.size())
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize references for symbol " + symbol, exception);
        }
    }
}
