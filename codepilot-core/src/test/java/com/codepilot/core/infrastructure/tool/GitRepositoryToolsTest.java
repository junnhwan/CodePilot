package com.codepilot.core.infrastructure.tool;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitRepositoryToolsTest {

    @TempDir
    Path repoRoot;

    @Test
    void readsBlameLogAndDiffContextFromRepositoryHistory() throws Exception {
        Files.createDirectories(repoRoot.resolve("src/main/java/com/example"));
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/UserRepository.java");

        try (Git git = Git.init().setDirectory(repoRoot.toFile()).call()) {
            writeJavaFile(sourceFile, """
                    package com.example;

                    class UserRepository {
                        String findById(String id) {
                            return "v1-" + id;
                        }
                    }
                    """);
            git.add().addFilepattern(".").call();
            git.commit()
                    .setAuthor("Alice", "alice@example.com")
                    .setCommitter("Alice", "alice@example.com")
                    .setMessage("feat: add repository baseline")
                    .call();

            writeJavaFile(sourceFile, """
                    package com.example;

                    class UserRepository {
                        String findById(String id) {
                            return "v2-" + id.trim();
                        }
                    }
                    """);
            git.add().addFilepattern(".").call();
            RevCommit headCommit = git.commit()
                    .setAuthor("Bob", "bob@example.com")
                    .setCommitter("Bob", "bob@example.com")
                    .setMessage("refactor: trim repository input")
                    .call();

            var blameResult = new GitBlameTool(repoRoot).execute(new com.codepilot.core.domain.tool.ToolCall(
                    "call-blame",
                    "git_blame",
                    Map.of(
                            "file_path", "src/main/java/com/example/UserRepository.java",
                            "start_line", 4,
                            "end_line", 5
                    )
            ));
            var logResult = new GitLogTool(repoRoot).execute(new com.codepilot.core.domain.tool.ToolCall(
                    "call-log",
                    "git_log",
                    Map.of(
                            "file_path", "src/main/java/com/example/UserRepository.java",
                            "max_commits", 2
                    )
            ));
            var diffResult = new GitDiffContextTool(repoRoot).execute(new com.codepilot.core.domain.tool.ToolCall(
                    "call-diff",
                    "git_diff_context",
                    Map.of(
                            "file_path", "src/main/java/com/example/UserRepository.java",
                            "base_ref", headCommit.getParent(0).getName(),
                            "head_ref", headCommit.getName(),
                            "context_lines", 1
                    )
            ));

            assertThat(blameResult.success()).isTrue();
            assertThat(blameResult.output()).contains("Bob").contains("return \"v2-\" + id.trim();");

            assertThat(logResult.success()).isTrue();
            assertThat(logResult.output()).contains("refactor: trim repository input");
            assertThat(logResult.output()).contains("feat: add repository baseline");

            assertThat(diffResult.success()).isTrue();
            assertThat(diffResult.output()).contains("@@");
            assertThat(diffResult.output()).contains("+        return \"v2-\" + id.trim();");
        }
    }

    private void writeJavaFile(Path sourceFile, String content) throws Exception {
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, content);
    }
}
