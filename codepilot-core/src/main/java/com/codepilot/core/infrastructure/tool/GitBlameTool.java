package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GitBlameTool implements Tool {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path repoRoot;

    public GitBlameTool(Path repoRoot) {
        this.repoRoot = RepositoryToolSupport.normalizeRepoRoot(repoRoot);
    }

    @Override
    public String name() {
        return "git_blame";
    }

    @Override
    public String description() {
        return "Read git blame information for a repository file line range.";
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
        String filePath = RepositoryToolSupport.normalizeRelativePath(String.valueOf(call.arguments().getOrDefault("file_path", "")));
        Path resolvedPath = RepositoryToolSupport.resolveInsideRepo(repoRoot, filePath);
        if (resolvedPath == null) {
            return ToolResult.failure(call.callId(), "file_path must stay inside the repository root", Map.of("filePath", filePath));
        }
        if (!Files.exists(resolvedPath)) {
            return ToolResult.failure(call.callId(), "file does not exist: " + filePath, Map.of("filePath", filePath));
        }

        try (Git git = Git.open(repoRoot.toFile())) {
            BlameResult blameResult = git.blame()
                    .setFilePath(filePath)
                    .call();
            if (blameResult == null) {
                return ToolResult.failure(call.callId(), "git blame returned no result for " + filePath, Map.of("filePath", filePath));
            }

            List<String> lines = Files.readAllLines(resolvedPath);
            int startLine = Math.min(RepositoryToolSupport.positiveInt(call.arguments().get("start_line"), 1), Math.max(lines.size(), 1));
            int endLine = Math.max(startLine, Math.min(RepositoryToolSupport.positiveInt(call.arguments().get("end_line"), lines.size()), lines.size()));

            StringBuilder output = new StringBuilder();
            for (int lineNumber = startLine; lineNumber <= endLine; lineNumber++) {
                int index = lineNumber - 1;
                RevCommit sourceCommit = blameResult.getSourceCommit(index);
                PersonIdent sourceAuthor = blameResult.getSourceAuthor(index);
                String authorName = sourceAuthor == null ? "UNKNOWN" : sourceAuthor.getName();
                String authoredAt = sourceAuthor == null
                        ? "UNKNOWN"
                        : TIME_FORMATTER.format(sourceAuthor.getWhenAsInstant().atOffset(ZoneOffset.UTC));
                String commitId = sourceCommit == null ? "UNCOMMITTED" : sourceCommit.getName().substring(0, 8);
                output.append("%4d| %s | %s | %s | %s%n".formatted(
                        lineNumber,
                        authorName,
                        authoredAt,
                        commitId,
                        lines.get(index)
                ));
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("filePath", filePath);
            metadata.put("startLine", startLine);
            metadata.put("endLine", endLine);
            return ToolResult.success(call.callId(), output.toString().trim(), metadata);
        } catch (IOException | GitAPIException exception) {
            return ToolResult.failure(call.callId(), "Failed to read git blame: " + exception.getMessage(), Map.of("filePath", filePath));
        }
    }
}
