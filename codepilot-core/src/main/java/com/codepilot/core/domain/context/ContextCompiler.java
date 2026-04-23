package com.codepilot.core.domain.context;

import com.codepilot.core.domain.memory.ProjectMemory;

import java.nio.file.Path;
import java.util.Map;

public interface ContextCompiler {

    ContextPack compile(
            Path repoRoot,
            String rawDiff,
            ProjectMemory projectMemory,
            Map<String, String> structuredFacts
    );
}
