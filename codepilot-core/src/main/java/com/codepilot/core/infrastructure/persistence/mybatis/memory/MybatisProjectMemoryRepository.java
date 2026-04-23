package com.codepilot.core.infrastructure.persistence.mybatis.memory;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisProjectMemoryRepository implements ProjectMemoryRepository {

    private final ReviewPatternMapper reviewPatternMapper;
    private final TeamConventionMapper teamConventionMapper;

    public MybatisProjectMemoryRepository(
            ReviewPatternMapper reviewPatternMapper,
            TeamConventionMapper teamConventionMapper
    ) {
        this.reviewPatternMapper = reviewPatternMapper;
        this.teamConventionMapper = teamConventionMapper;
    }

    @Override
    public Optional<ProjectMemory> findByProjectId(String projectId) {
        List<ReviewPattern> reviewPatterns = reviewPatternMapper.selectByProjectId(projectId).stream()
                .map(this::toDomain)
                .toList();
        List<TeamConvention> teamConventions = teamConventionMapper.selectByProjectId(projectId).stream()
                .map(this::toDomain)
                .toList();

        if (reviewPatterns.isEmpty() && teamConventions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProjectMemory(projectId, reviewPatterns, teamConventions));
    }

    @Override
    public void save(ProjectMemory projectMemory) {
        reviewPatternMapper.deleteByProjectId(projectMemory.projectId());
        teamConventionMapper.deleteByProjectId(projectMemory.projectId());

        if (!projectMemory.reviewPatterns().isEmpty()) {
            reviewPatternMapper.insertAll(projectMemory.reviewPatterns().stream()
                    .map(this::toRow)
                    .toList());
        }
        if (!projectMemory.teamConventions().isEmpty()) {
            teamConventionMapper.insertAll(projectMemory.teamConventions().stream()
                    .map(this::toRow)
                    .toList());
        }
    }

    private ReviewPatternRow toRow(ReviewPattern reviewPattern) {
        return new ReviewPatternRow(
                reviewPattern.patternId(),
                reviewPattern.projectId(),
                reviewPattern.patternType().name(),
                reviewPattern.title(),
                reviewPattern.description(),
                reviewPattern.codeExample(),
                reviewPattern.frequency(),
                reviewPattern.lastSeenAt()
        );
    }

    private ReviewPattern toDomain(ReviewPatternRow reviewPatternRow) {
        return new ReviewPattern(
                reviewPatternRow.patternId(),
                reviewPatternRow.projectId(),
                ReviewPattern.PatternType.valueOf(reviewPatternRow.patternType()),
                reviewPatternRow.title(),
                reviewPatternRow.description(),
                reviewPatternRow.codeExample(),
                reviewPatternRow.frequency(),
                reviewPatternRow.lastSeenAt()
        );
    }

    private TeamConventionRow toRow(TeamConvention teamConvention) {
        return new TeamConventionRow(
                teamConvention.conventionId(),
                teamConvention.projectId(),
                teamConvention.category().name(),
                teamConvention.rule(),
                teamConvention.exampleGood(),
                teamConvention.exampleBad(),
                teamConvention.confidence(),
                teamConvention.source().name()
        );
    }

    private TeamConvention toDomain(TeamConventionRow teamConventionRow) {
        return new TeamConvention(
                teamConventionRow.conventionId(),
                teamConventionRow.projectId(),
                TeamConvention.Category.valueOf(teamConventionRow.category()),
                teamConventionRow.rule(),
                teamConventionRow.exampleGood(),
                teamConventionRow.exampleBad(),
                teamConventionRow.confidence(),
                TeamConvention.Source.valueOf(teamConventionRow.source())
        );
    }
}
