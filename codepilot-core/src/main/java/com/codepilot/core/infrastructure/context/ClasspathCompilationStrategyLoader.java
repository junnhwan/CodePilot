package com.codepilot.core.infrastructure.context;

import com.codepilot.core.domain.context.CompilationStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

public final class ClasspathCompilationStrategyLoader {

    private final ObjectMapper objectMapper;

    public ClasspathCompilationStrategyLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CompilationStrategy load(String profileId) {
        String resourcePath = "compilation-profiles/" + profileId + ".json";
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Compilation strategy profile not found: " + resourcePath);
            }
            return objectMapper.readValue(inputStream, CompilationStrategy.class);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load compilation strategy profile " + resourcePath, error);
        }
    }
}
