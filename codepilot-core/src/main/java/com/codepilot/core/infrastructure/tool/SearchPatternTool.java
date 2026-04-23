package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SearchPatternTool implements Tool {

    private final Path repoRoot;

    public SearchPatternTool(Path repoRoot) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "search_pattern";
    }

    @Override
    public String description() {
        return "Search the repository for a regex pattern and return matching file lines.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of("type", "string"),
                        "max_results", Map.of("type", "integer")
                ),
                "required", List.of("pattern")
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
        String rawPattern = String.valueOf(call.arguments().getOrDefault("pattern", ""));
        if (rawPattern.isBlank()) {
            return ToolResult.failure(call.callId(), "pattern must not be blank", Map.of());
        }

        final Pattern pattern;
        try {
            pattern = Pattern.compile(rawPattern);
        } catch (PatternSyntaxException error) {
            return ToolResult.failure(call.callId(), "invalid regex pattern: " + rawPattern, Map.of("pattern", rawPattern));
        }

        int maxResults = call.arguments().get("max_results") instanceof Number number && number.intValue() > 0
                ? number.intValue()
                : 20;

        List<String> matches = new ArrayList<>();
        try (var files = Files.walk(repoRoot)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                List<String> lines = Files.readAllLines(path);
                for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
                    String line = lines.get(lineNumber);
                    if (pattern.matcher(line).find()) {
                        matches.add(repoRoot.relativize(path) + ":" + (lineNumber + 1) + ": " + line.trim());
                        if (matches.size() >= maxResults) {
                            return ToolResult.success(call.callId(), String.join(System.lineSeparator(), matches), Map.of("matchCount", matches.size()));
                        }
                    }
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to search repository for pattern " + rawPattern, error);
        }

        return ToolResult.success(call.callId(), String.join(System.lineSeparator(), matches), Map.of("matchCount", matches.size()));
    }
}
