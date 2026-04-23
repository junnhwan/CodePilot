package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReadFileTool implements Tool {

    private final Path repoRoot;

    public ReadFileTool(Path repoRoot) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a repository file and optionally limit the returned line range.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string"),
                        "start_line", Map.of("type", "integer"),
                        "end_line", Map.of("type", "integer")
                ),
                "required", List.of("file_path")
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
        String filePath = String.valueOf(call.arguments().getOrDefault("file_path", ""));
        Path resolvedPath = resolvePath(filePath);
        if (resolvedPath == null) {
            return ToolResult.failure(call.callId(), "file_path must stay inside the repository root", Map.of("filePath", filePath));
        }
        if (!Files.exists(resolvedPath)) {
            return ToolResult.failure(call.callId(), "file does not exist: " + filePath, Map.of("filePath", filePath));
        }

        try {
            List<String> lines = Files.readAllLines(resolvedPath);
            int startLine = toPositiveInt(call.arguments().get("start_line"), 1);
            int endLine = toPositiveInt(call.arguments().get("end_line"), lines.size());
            startLine = Math.min(startLine, Math.max(lines.size(), 1));
            endLine = Math.max(startLine, Math.min(endLine, lines.size()));

            StringBuilder output = new StringBuilder();
            for (int lineNumber = startLine; lineNumber <= endLine; lineNumber++) {
                output.append("%4d| %s%n".formatted(lineNumber, lines.get(lineNumber - 1)));
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("filePath", filePath);
            metadata.put("startLine", startLine);
            metadata.put("endLine", endLine);
            return ToolResult.success(call.callId(), output.toString().trim(), metadata);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read file " + filePath, error);
        }
    }

    private Path resolvePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path resolvedPath = repoRoot.resolve(filePath).normalize();
        return resolvedPath.startsWith(repoRoot) ? resolvedPath : null;
    }

    private int toPositiveInt(Object rawValue, int defaultValue) {
        if (rawValue instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return defaultValue;
    }
}
