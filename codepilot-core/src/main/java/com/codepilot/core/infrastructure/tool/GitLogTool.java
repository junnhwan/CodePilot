package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GitLogTool implements Tool {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path repoRoot;

    public GitLogTool(Path repoRoot) {
        this.repoRoot = RepositoryToolSupport.normalizeRepoRoot(repoRoot);
    }

    @Override
    public String name() {
        return "git_log";
    }

    @Override
    public String description() {
        return "Read recent git commit history for a repository file.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string"),
                        "max_commits", Map.of("type", "integer")
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

        int maxCommits = RepositoryToolSupport.positiveInt(call.arguments().get("max_commits"), 10);

        try (Git git = Git.open(repoRoot.toFile())) {
            Iterable<RevCommit> commits = git.log()
                    .addPath(filePath)
                    .setMaxCount(maxCommits)
                    .call();
            StringBuilder output = new StringBuilder();
            int commitCount = 0;
            for (RevCommit commit : commits) {
                commitCount++;
                output.append("%s | %s | %s | %s%n".formatted(
                        commit.getName().substring(0, 8),
                        commit.getAuthorIdent().getName(),
                        TIME_FORMATTER.format(commit.getAuthorIdent().getWhenAsInstant().atOffset(ZoneOffset.UTC)),
                        commit.getShortMessage()
                ));
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("filePath", filePath);
            metadata.put("commitCount", commitCount);
            return ToolResult.success(call.callId(), output.toString().trim(), metadata);
        } catch (IOException exception) {
            return ToolResult.failure(call.callId(), "Failed to read git log: " + exception.getMessage(), Map.of("filePath", filePath));
        } catch (Exception exception) {
            return ToolResult.failure(call.callId(), "Failed to load git history: " + exception.getMessage(), Map.of("filePath", filePath));
        }
    }
}
