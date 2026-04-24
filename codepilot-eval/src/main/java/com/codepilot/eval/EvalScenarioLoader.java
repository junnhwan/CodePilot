package com.codepilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
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

    public List<EvalScenario> load(String resourcePath) {
        try (InputStream inputStream = currentClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Eval scenario pack not found: " + resourcePath);
            }
            ScenarioPack scenarioPack = objectMapper.readValue(inputStream, ScenarioPack.class);
            return scenarioPack.scenarios();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load eval scenario pack " + resourcePath, error);
        }
    }

    private ClassLoader currentClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : EvalScenarioLoader.class.getClassLoader();
    }

    private record ScenarioPack(
            List<EvalScenario> scenarios
    ) {

        private ScenarioPack {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }
}
