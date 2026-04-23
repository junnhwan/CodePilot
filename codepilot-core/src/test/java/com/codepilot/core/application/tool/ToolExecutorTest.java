package com.codepilot.core.application.tool;

import com.codepilot.core.domain.tool.ToolCall;
import com.codepilot.core.domain.tool.ToolResult;
import com.codepilot.core.infrastructure.tool.AstParseTool;
import com.codepilot.core.infrastructure.tool.ReadFileTool;
import com.codepilot.core.infrastructure.tool.SearchPatternTool;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {

    @TempDir
    Path repoRoot;

    @Test
    void executesRegisteredReadOnlyToolsAgainstRepository() throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/DemoRepository.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                class DemoRepository {
                    String findById(String id) {
                        return jdbcTemplate.queryForObject("select * from demo where id = ?", String.class, id);
                    }
                }
                """);

        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new ReadFileTool(repoRoot),
                new SearchPatternTool(repoRoot),
                new AstParseTool(repoRoot, JsonMapper.builder().findAndAddModules().build())
        ));
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry);

        List<ToolResult> results = toolExecutor.executeAll(List.of(
                new ToolCall("call-read", "read_file", Map.of("file_path", "src/main/java/com/example/DemoRepository.java")),
                new ToolCall("call-search", "search_pattern", Map.of("pattern", "jdbcTemplate\\.queryForObject")),
                new ToolCall("call-ast", "ast_parse", Map.of("file_path", "src/main/java/com/example/DemoRepository.java"))
        ));

        assertThat(results).hasSize(3);
        assertThat(results).allMatch(ToolResult::success);
        assertThat(results.get(0).output()).contains("class DemoRepository");
        assertThat(results.get(1).output()).contains("DemoRepository.java:5");
        assertThat(results.get(2).output()).contains("findById");
    }
}
