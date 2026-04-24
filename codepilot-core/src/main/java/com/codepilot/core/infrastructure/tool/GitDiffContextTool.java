package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.tool.Tool;
import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GitDiffContextTool implements Tool {

    private final Path repoRoot;

    public GitDiffContextTool(Path repoRoot) {
        this.repoRoot = RepositoryToolSupport.normalizeRepoRoot(repoRoot);
    }

    @Override
    public String name() {
        return "git_diff_context";
    }

    @Override
    public String description() {
        return "Read a file diff between two git refs with configurable context lines.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of("type", "string"),
                        "base_ref", Map.of("type", "string"),
                        "head_ref", Map.of("type", "string"),
                        "context_lines", Map.of("type", "integer")
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
        if (RepositoryToolSupport.resolveInsideRepo(repoRoot, filePath) == null) {
            return ToolResult.failure(call.callId(), "file_path must stay inside the repository root", Map.of("filePath", filePath));
        }

        String baseRef = String.valueOf(call.arguments().getOrDefault("base_ref", "HEAD~1")).trim();
        String headRef = String.valueOf(call.arguments().getOrDefault("head_ref", "HEAD")).trim();
        int contextLines = RepositoryToolSupport.positiveInt(call.arguments().get("context_lines"), 3);

        try (Git git = Git.open(repoRoot.toFile());
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             DiffFormatter formatter = new DiffFormatter(output)) {
            formatter.setRepository(git.getRepository());
            formatter.setContext(contextLines);
            formatter.setDetectRenames(true);

            try (var reader = git.getRepository().newObjectReader()) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, git.getRepository().resolve(baseRef + "^{tree}"));
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, git.getRepository().resolve(headRef + "^{tree}"));

                List<DiffEntry> entries = formatter.scan(oldTree, newTree);
                int matchedEntries = 0;
                for (DiffEntry entry : entries) {
                    String oldPath = entry.getOldPath() == null ? "" : entry.getOldPath();
                    String newPath = entry.getNewPath() == null ? "" : entry.getNewPath();
                    if (!filePath.equals(oldPath) && !filePath.equals(newPath)) {
                        continue;
                    }
                    formatter.format(entry);
                    matchedEntries++;
                }

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("filePath", filePath);
                metadata.put("baseRef", baseRef);
                metadata.put("headRef", headRef);
                metadata.put("matchedEntries", matchedEntries);

                if (matchedEntries == 0) {
                    return ToolResult.failure(call.callId(), "No diff entry found for " + filePath, metadata);
                }
                return ToolResult.success(call.callId(), output.toString(StandardCharsets.UTF_8).trim(), metadata);
            }
        } catch (IOException exception) {
            return ToolResult.failure(call.callId(), "Failed to read git diff: " + exception.getMessage(), Map.of("filePath", filePath));
        }
    }
}
