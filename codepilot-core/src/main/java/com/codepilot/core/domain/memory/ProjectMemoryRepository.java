package com.codepilot.core.domain.memory;

import java.util.Optional;

public interface ProjectMemoryRepository {

    Optional<ProjectMemory> findByProjectId(String projectId);

    void save(ProjectMemory projectMemory);

    default ProjectMemory load(String projectId) {
        return findByProjectId(projectId).orElseGet(() -> ProjectMemory.empty(projectId));
    }
}
