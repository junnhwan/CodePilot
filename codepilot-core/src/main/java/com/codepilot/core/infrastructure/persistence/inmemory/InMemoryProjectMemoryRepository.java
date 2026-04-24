package com.codepilot.core.infrastructure.persistence.inmemory;

import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ProjectMemoryRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryProjectMemoryRepository implements ProjectMemoryRepository {

    private final Map<String, ProjectMemory> storage = new ConcurrentHashMap<>();

    @Override
    public Optional<ProjectMemory> findByProjectId(String projectId) {
        return Optional.ofNullable(storage.get(projectId));
    }

    @Override
    public void save(ProjectMemory projectMemory) {
        storage.put(projectMemory.projectId(), projectMemory);
    }
}
