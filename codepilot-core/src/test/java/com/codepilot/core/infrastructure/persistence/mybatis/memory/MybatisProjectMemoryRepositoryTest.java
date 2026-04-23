package com.codepilot.core.infrastructure.persistence.mybatis.memory;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisProjectMemoryRepositoryTest {

    @Test
    void savesAndRestoresProjectMemoryAggregate() {
        InMemoryReviewPatternMapper reviewPatternMapper = new InMemoryReviewPatternMapper();
        InMemoryTeamConventionMapper teamConventionMapper = new InMemoryTeamConventionMapper();
        MybatisProjectMemoryRepository repository = new MybatisProjectMemoryRepository(reviewPatternMapper, teamConventionMapper);

        ProjectMemory projectMemory = ProjectMemory.empty("project-alpha")
                .addPattern(new ReviewPattern(
                        "pattern-1",
                        "project-alpha",
                        ReviewPattern.PatternType.SECURITY_PATTERN,
                        "Missing validation",
                        "Controller paths frequently skip request validation.",
                        "request.get(\"id\")",
                        2,
                        Instant.parse("2026-04-23T00:00:00Z")
                ))
                .addConvention(new TeamConvention(
                        "conv-1",
                        "project-alpha",
                        TeamConvention.Category.ARCHITECTURE,
                        "Gateway only receives webhook and query traffic.",
                        "WebhookReceiver delegates into core use case",
                        "Gateway writes directly into domain storage",
                        0.95d,
                        TeamConvention.Source.MANUAL
                ));

        repository.save(projectMemory);
        Optional<ProjectMemory> restored = repository.findByProjectId("project-alpha");

        assertThat(restored).isPresent();
        assertThat(restored.orElseThrow().reviewPatterns())
                .extracting(ReviewPattern::patternId)
                .containsExactly("pattern-1");
        assertThat(restored.orElseThrow().teamConventions())
                .extracting(TeamConvention::conventionId)
                .containsExactly("conv-1");
    }

    private static final class InMemoryReviewPatternMapper implements ReviewPatternMapper {

        private final List<ReviewPatternRow> rows = new ArrayList<>();

        @Override
        public List<ReviewPatternRow> selectByProjectId(String projectId) {
            return rows.stream()
                    .filter(row -> row.projectId().equals(projectId))
                    .sorted(Comparator.comparing(ReviewPatternRow::patternId))
                    .toList();
        }

        @Override
        public void deleteByProjectId(String projectId) {
            rows.removeIf(row -> row.projectId().equals(projectId));
        }

        @Override
        public void insertAll(List<ReviewPatternRow> reviewPatterns) {
            rows.addAll(reviewPatterns);
        }
    }

    private static final class InMemoryTeamConventionMapper implements TeamConventionMapper {

        private final List<TeamConventionRow> rows = new ArrayList<>();

        @Override
        public List<TeamConventionRow> selectByProjectId(String projectId) {
            return rows.stream()
                    .filter(row -> row.projectId().equals(projectId))
                    .sorted(Comparator.comparing(TeamConventionRow::conventionId))
                    .toList();
        }

        @Override
        public void deleteByProjectId(String projectId) {
            rows.removeIf(row -> row.projectId().equals(projectId));
        }

        @Override
        public void insertAll(List<TeamConventionRow> teamConventions) {
            rows.addAll(teamConventions);
        }
    }
}
