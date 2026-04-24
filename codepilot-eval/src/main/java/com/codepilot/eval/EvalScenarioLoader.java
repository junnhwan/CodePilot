package com.codepilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

public final class EvalScenarioLoader {

    public static final String DEFAULT_SCENARIO_PACK = "eval/scenarios/minimal-scenario-pack.json";

    private final ObjectMapper objectMapper;

    public EvalScenarioLoader() {
        this(JsonMapper.builder().findAndAddModules().build());
    }

    EvalScenarioLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvalScenario> loadDefaultScenarios() {
        return load(DEFAULT_SCENARIO_PACK);
    }

    public List<EvalScenario> load(String scenarioPackSource) {
        Path filesystemPath = toExistingPath(scenarioPackSource);
        if (filesystemPath != null) {
            return load(filesystemPath);
        }

        try (InputStream inputStream = currentClassLoader().getResourceAsStream(scenarioPackSource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Eval scenario pack not found: " + scenarioPackSource);
            }
            return readScenarioPack(inputStream, scenarioPackSource);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load eval scenario pack " + scenarioPackSource, error);
        }
    }

    public List<EvalScenario> load(Path scenarioPackPath) {
        Path normalizedPath = scenarioPackPath.toAbsolutePath().normalize();
        try (InputStream inputStream = Files.newInputStream(normalizedPath)) {
            return readScenarioPack(inputStream, normalizedPath.toString());
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load eval scenario pack " + normalizedPath, error);
        }
    }

    private ClassLoader currentClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : EvalScenarioLoader.class.getClassLoader();
    }

    private List<EvalScenario> readScenarioPack(InputStream inputStream, String source) throws IOException {
        ScenarioPack scenarioPack = objectMapper.readValue(inputStream, ScenarioPack.class);
        if (scenarioPack.scenarios().isEmpty()) {
            throw new IllegalStateException("Eval scenario pack has no scenarios: " + source);
        }
        return scenarioPack.scenarios();
    }

    private Path toExistingPath(String scenarioPackSource) {
        if (scenarioPackSource == null || scenarioPackSource.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(scenarioPackSource);
            return Files.exists(path) ? path : null;
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private record ScenarioPack(
            List<EvalScenario> scenarios
    ) {

        private ScenarioPack {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }
}
