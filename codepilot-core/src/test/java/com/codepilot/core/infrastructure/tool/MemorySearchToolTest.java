package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import com.codepilot.core.domain.tool.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySearchToolTest {

    @Test
    void searchesProjectMemoryWithMinimalKeywordMatching() {
        ProjectMemoryRepository repository = new ProjectMemoryRepository() {
            @Override
            public Optional<ProjectMemory> findByProjectId(String projectId) {
                return Optional.of(ProjectMemory.empty(projectId)
                        .addPattern(new ReviewPattern(
                                "pattern-1",
                                projectId,
                                ReviewPattern.PatternType.SECURITY_PATTERN,
                                "Validation missing before repository call",
                                "Controllers in this project often skip validation before DAO access.",
                                "repository.findById(request.id())",
                                3,
                                Instant.parse("2026-04-23T00:00:00Z")
                        ))
                        .addConvention(new TeamConvention(
                                "conv-1",
                                projectId,
                                TeamConvention.Category.ARCHITECTURE,
                                "Gateway should stay thin and delegate validation into core.",
                                "Gateway delegates into use case after request validation.",
                                "Gateway writes domain state directly.",
                                0.9d,
                                TeamConvention.Source.MANUAL
                        )));
            }

            @Override
            public void save(ProjectMemory projectMemory) {
                throw new UnsupportedOperationException("save is not used in this test");
            }
        };

        MemorySearchTool memorySearchTool = new MemorySearchTool(repository, "project-alpha");
        var result = memorySearchTool.execute(new ToolCall(
                "call-memory",
                "memory_search",
                Map.of(
                        "query", "validation gateway",
                        "max_results", 5
                )
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("Validation missing before repository call");
        assertThat(result.output()).contains("Gateway should stay thin");
    }
}
