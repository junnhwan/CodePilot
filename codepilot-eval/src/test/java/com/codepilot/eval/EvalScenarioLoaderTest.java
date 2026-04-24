package com.codepilot.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvalScenarioLoaderTest {

    @Test
    void loadsDefaultScenarioPackWithRealFixtures() {
        EvalScenarioLoader loader = new EvalScenarioLoader();

        var scenarios = loader.loadDefaultScenarios();

        assertThat(scenarios)
                .extracting(EvalScenario::scenarioId)
                .containsExactly(
                        "eval-sql-injection-001",
                        "eval-hardcoded-token-001",
                        "eval-cross-file-token-guard-001",
                        "eval-repository-loop-001",
                        "eval-repository-loop-002",
                        "eval-safe-refactor-001",
                        "eval-swallowed-exception-001"
                );
        EvalScenario memoryScenario = scenarios.stream()
                .filter(scenario -> scenario.scenarioId().equals("eval-cross-file-token-guard-001"))
                .findFirst()
                .orElseThrow();
        assertThat(memoryScenario.projectMemory().reviewPatterns()).hasSize(1);
        assertThat(memoryScenario.rawDiff()).contains("TokenController");
        assertThat(memoryScenario.groundTruth()).singleElement()
                .extracting(EvalScenario.GroundTruthFinding::title)
                .isEqualTo("Missing token guard before repository access");
    }

    @Test
    void loadsScenarioPackFromFilesystem(@TempDir Path tempDir) throws IOException {
        Path scenarioPack = tempDir.resolve("scenario-pack.json");
        Files.writeString(scenarioPack, """
                {
                  "scenarios": [
                    {
                      "scenarioId": "eval-file-pack-001",
                      "name": "File-backed pack",
                      "description": "Load one scenario from the filesystem.",
                      "projectId": "file-pack-project",
                      "repositoryFiles": [
                        {
                          "path": "src/main/java/com/example/FilePack.java",
                          "lines": ["class FilePack {}"]
                        }
                      ],
                      "diffLines": [
                        "diff --git a/src/main/java/com/example/FilePack.java b/src/main/java/com/example/FilePack.java"
                      ],
                      "groundTruth": []
                    }
                  ]
                }
                """);

        EvalScenarioLoader loader = new EvalScenarioLoader();

        var scenarios = loader.load(scenarioPack);

        assertThat(scenarios).singleElement()
                .extracting(EvalScenario::scenarioId)
                .isEqualTo("eval-file-pack-001");
    }

    @Test
    void resolvesRelativeIncludesFromFilesystem(@TempDir Path tempDir) throws IOException {
        Path nestedPack = tempDir.resolve("nested-pack.json");
        Files.writeString(nestedPack, """
                {
                  "scenarios": [
                    {
                      "scenarioId": "eval-file-include-001",
                      "name": "Included scenario",
                      "description": "Loaded from a nested pack.",
                      "projectId": "include-pack-project",
                      "repositoryFiles": [
                        {
                          "path": "src/main/java/com/example/Included.java",
                          "lines": ["class Included {}"]
                        }
                      ],
                      "diffLines": [
                        "diff --git a/src/main/java/com/example/Included.java b/src/main/java/com/example/Included.java"
                      ],
                      "groundTruth": []
                    }
                  ]
                }
                """);
        Path rootPack = tempDir.resolve("root-pack.json");
        Files.writeString(rootPack, """
                {
                  "includes": ["nested-pack.json"],
                  "scenarios": [
                    {
                      "scenarioId": "eval-root-pack-001",
                      "name": "Root scenario",
                      "description": "Loaded from the root pack.",
                      "projectId": "root-pack-project",
                      "repositoryFiles": [
                        {
                          "path": "src/main/java/com/example/Root.java",
                          "lines": ["class Root {}"]
                        }
                      ],
                      "diffLines": [
                        "diff --git a/src/main/java/com/example/Root.java b/src/main/java/com/example/Root.java"
                      ],
                      "groundTruth": []
                    }
                  ]
                }
                """);

        EvalScenarioLoader loader = new EvalScenarioLoader();

        var scenarios = loader.load(rootPack);

        assertThat(scenarios)
                .extracting(EvalScenario::scenarioId)
                .containsExactly("eval-file-include-001", "eval-root-pack-001");
    }
}
