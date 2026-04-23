package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class AstParseTool implements Tool {

    private final Path repoRoot;

    private final ObjectMapper objectMapper;

    private final JavaParser javaParser;

    public AstParseTool(Path repoRoot, ObjectMapper objectMapper) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.javaParser = new JavaParser();
    }

    @Override
    public String name() {
        return "ast_parse";
    }

    @Override
    public String description() {
        return "Parse a Java source file and return package, class, field, and method symbols.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string")
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

        final ParseResult<CompilationUnit> parseResult;
        try {
            parseResult = javaParser.parse(resolvedPath);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to parse Java file " + filePath, error);
        }

        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            String problems = parseResult.getProblems().stream().map(Object::toString).reduce((left, right) -> left + System.lineSeparator() + right).orElse("unknown parse error");
            return ToolResult.failure(call.callId(), "ast_parse failed: " + problems, Map.of("filePath", filePath));
        }

        CompilationUnit unit = parseResult.getResult().orElseThrow();
        AstSummary summary = new AstSummary(
                unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse(""),
                unit.findAll(ClassOrInterfaceDeclaration.class).stream().map(ClassOrInterfaceDeclaration::getNameAsString).toList(),
                unit.findAll(FieldDeclaration.class).stream()
                        .flatMap(field -> field.getVariables().stream().map(variable -> field.getElementType().asString() + " " + variable.getNameAsString()))
                        .toList(),
                unit.findAll(MethodDeclaration.class).stream()
                        .map(method -> "%s %s(%s)".formatted(
                                method.getType().asString(),
                                method.getNameAsString(),
                                method.getParameters().stream()
                                        .map(parameter -> parameter.getType().asString() + " " + parameter.getNameAsString())
                                        .reduce((left, right) -> left + ", " + right)
                                        .orElse("")
                        ))
                        .toList()
        );

        try {
            return ToolResult.success(
                    call.callId(),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                    Map.of("filePath", filePath, "classCount", summary.classes().size(), "methodCount", summary.methods().size())
            );
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize ast summary for " + filePath, error);
        }
    }

    private Path resolvePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path resolvedPath = repoRoot.resolve(filePath).normalize();
        return resolvedPath.startsWith(repoRoot) ? resolvedPath : null;
    }

    private record AstSummary(
            String packageName,
            List<String> classes,
            List<String> fields,
            List<String> methods
    ) {
    }
}
