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
                        "eval-repository-loop-001",
                        "eval-project-token-guard-001",
                        "eval-safe-refactor-001"
                );
        EvalScenario memoryScenario = scenarios.stream()
                .filter(scenario -> scenario.scenarioId().equals("eval-project-token-guard-001"))
                .findFirst()
                .orElseThrow();
        assertThat(memoryScenario.projectMemory().reviewPatterns()).hasSize(1);
        assertThat(memoryScenario.rawDiff()).contains("ProjectTokenService");
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
}
