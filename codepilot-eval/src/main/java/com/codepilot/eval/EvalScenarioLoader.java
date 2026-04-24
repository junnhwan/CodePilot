package com.codepilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class EvalScenarioLoader {

    public static final String DEFAULT_SCENARIO_PACK = "eval/scenarios/expanded-scenario-pack.json";

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
            return load(filesystemPath.toAbsolutePath().normalize(), new LinkedHashSet<>());
        }
        return loadClasspath(resolveResourcePath(null, scenarioPackSource), new LinkedHashSet<>());
    }

    public List<EvalScenario> load(Path scenarioPackPath) {
        return load(scenarioPackPath.toAbsolutePath().normalize(), new LinkedHashSet<>());
    }

    private ClassLoader currentClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : EvalScenarioLoader.class.getClassLoader();
    }

    private List<EvalScenario> loadClasspath(String resourcePath, LinkedHashSet<String> visitedPacks) {
        String visitKey = "classpath:" + resourcePath;
        if (!visitedPacks.add(visitKey)) {
            throw new IllegalStateException("Eval scenario pack include cycle detected: " + resourcePath);
        }
        try (InputStream inputStream = currentClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Eval scenario pack not found: " + resourcePath);
            }
            return mergeScenarios(
                    resourcePath,
                    readScenarioPack(inputStream, resourcePath),
                    include -> loadClasspath(resolveResourcePath(resourcePath, include), visitedPacks)
            );
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load eval scenario pack " + resourcePath, error);
        } finally {
            visitedPacks.remove(visitKey);
        }
    }

    private List<EvalScenario> load(Path scenarioPackPath, LinkedHashSet<String> visitedPacks) {
        String visitKey = scenarioPackPath.toString();
        if (!visitedPacks.add(visitKey)) {
            throw new IllegalStateException("Eval scenario pack include cycle detected: " + scenarioPackPath);
        }
        try (InputStream inputStream = Files.newInputStream(scenarioPackPath)) {
            return mergeScenarios(
                    scenarioPackPath.toString(),
                    readScenarioPack(inputStream, scenarioPackPath.toString()),
                    include -> load(resolvePath(scenarioPackPath, include), visitedPacks)
            );
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load eval scenario pack " + scenarioPackPath, error);
        } finally {
            visitedPacks.remove(visitKey);
        }
    }

    private ScenarioPack readScenarioPack(InputStream inputStream, String source) throws IOException {
        ScenarioPack scenarioPack = objectMapper.readValue(inputStream, ScenarioPack.class);
        if (scenarioPack.includes().isEmpty() && scenarioPack.scenarios().isEmpty()) {
            throw new IllegalStateException("Eval scenario pack has no scenarios: " + source);
        }
        return scenarioPack;
    }

    private List<EvalScenario> mergeScenarios(
            String source,
            ScenarioPack scenarioPack,
            IncludeLoader includeLoader
    ) {
        List<EvalScenario> merged = new ArrayList<>();
        for (String include : scenarioPack.includes()) {
            merged.addAll(includeLoader.load(include));
        }
        merged.addAll(scenarioPack.scenarios());

        Map<String, EvalScenario> uniqueById = new LinkedHashMap<>();
        for (EvalScenario scenario : merged) {
            EvalScenario previous = uniqueById.putIfAbsent(scenario.scenarioId(), scenario);
            if (previous != null) {
                throw new IllegalStateException("Duplicate eval scenario id %s in %s"
                        .formatted(scenario.scenarioId(), source));
            }
        }
        return List.copyOf(uniqueById.values());
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

    private Path resolvePath(Path basePath, String include) {
        Path includePath = Path.of(include);
        if (includePath.isAbsolute()) {
            return includePath.normalize();
        }
        return basePath.getParent().resolve(includePath).normalize();
    }

    private String resolveResourcePath(String baseResource, String include) {
        String normalizedInclude = include == null ? "" : include.trim();
        if (normalizedInclude.isBlank()) {
            throw new IllegalArgumentException("Eval scenario include must not be blank");
        }
        if (normalizedInclude.startsWith("/")) {
            normalizedInclude = normalizedInclude.substring(1);
        }
        if (baseResource == null || baseResource.isBlank() || normalizedInclude.contains("/")) {
            return normalizedInclude;
        }
        int separator = baseResource.lastIndexOf('/');
        if (separator < 0) {
            return normalizedInclude;
        }
        return baseResource.substring(0, separator + 1) + normalizedInclude;
    }

    private record ScenarioPack(
            List<String> includes,
            List<EvalScenario> scenarios
    ) {

        private ScenarioPack {
            includes = includes == null ? List.of() : List.copyOf(includes);
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }

    @FunctionalInterface
    private interface IncludeLoader {
        List<EvalScenario> load(String include);
    }
}
