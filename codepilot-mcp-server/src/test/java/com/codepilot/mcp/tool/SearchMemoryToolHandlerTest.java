package com.codepilot.mcp.tool;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SearchMemoryToolHandlerTest {

    @Test
    void returnsStructuredMatchesForRelevantProjectMemoryEntries() {
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

        SearchMemoryToolHandler handler = new SearchMemoryToolHandler(repository);
        McpSchema.CallToolResult result = handler.apply(null, McpSchema.CallToolRequest.builder()
                .name("search_memory")
                .arguments(Map.of(
                        "project_id", "project-alpha",
                        "query", "validation gateway",
                        "max_results", 5
                ))
                .build());

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured).containsEntry("project_id", "project-alpha");
        assertThat(structured).containsEntry("match_count", 2);
        assertThat(((List<?>) structured.get("matches"))).hasSize(2);
        assertThat(result.content().toString()).contains("Validation missing before repository call");
        assertThat(result.content().toString()).contains("Gateway should stay thin");
    }

    @Test
    void returnsValidationErrorWhenQueryIsBlank() {
        SearchMemoryToolHandler handler = new SearchMemoryToolHandler(new ProjectMemoryRepository() {
            @Override
            public Optional<ProjectMemory> findByProjectId(String projectId) {
                return Optional.of(ProjectMemory.empty(projectId));
            }

            @Override
            public void save(ProjectMemory projectMemory) {
                throw new UnsupportedOperationException("save is not used in this test");
            }
        });

        McpSchema.CallToolResult result = handler.apply(null, McpSchema.CallToolRequest.builder()
                .name("search_memory")
                .arguments(Map.of(
                        "project_id", "project-alpha",
                        "query", "   "
                ))
                .build());

        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString()).contains("search_memory validation failed");
        assertThat(result.content().toString()).contains("query");
    }
}
