package com.codepilot.core.domain.memory;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

public record ProjectMemory(
        String projectId,
        List<ReviewPattern> reviewPatterns,
        List<TeamConvention> teamConventions
) {

    public ProjectMemory {
        projectId = requireText(projectId, "projectId");
        reviewPatterns = reviewPatterns == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(reviewPatterns));
        teamConventions = teamConventions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(teamConventions));

        for (ReviewPattern reviewPattern : reviewPatterns) {
            assertProject(reviewPattern.projectId(), projectId, reviewPattern.patternId());
        }
        for (TeamConvention teamConvention : teamConventions) {
            assertProject(teamConvention.projectId(), projectId, teamConvention.conventionId());
        }
    }

    public static ProjectMemory empty(String projectId) {
        return new ProjectMemory(projectId, List.of(), List.of());
    }

    public ProjectMemory addPattern(ReviewPattern reviewPattern) {
        assertProject(reviewPattern.projectId(), projectId, reviewPattern.patternId());
        LinkedHashMap<String, ReviewPattern> indexed = new LinkedHashMap<>();
        reviewPatterns.forEach(pattern -> indexed.put(pattern.patternId(), pattern));
        indexed.put(reviewPattern.patternId(), reviewPattern);
        return new ProjectMemory(projectId, List.copyOf(indexed.values()), teamConventions);
    }

    public ProjectMemory addConvention(TeamConvention convention) {
        assertProject(convention.projectId(), projectId, convention.conventionId());
        LinkedHashMap<String, TeamConvention> indexed = new LinkedHashMap<>();
        teamConventions.forEach(existing -> indexed.put(existing.conventionId(), existing));
        indexed.put(convention.conventionId(), convention);
        return new ProjectMemory(projectId, reviewPatterns, List.copyOf(indexed.values()));
    }

    private static void assertProject(String candidateProjectId, String expectedProjectId, String itemId) {
        if (!expectedProjectId.equals(candidateProjectId)) {
            throw new DomainRuleViolationException("ProjectMemory[%s] cannot include item %s from project %s"
                    .formatted(expectedProjectId, itemId, candidateProjectId));
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
