package com.codepilot.eval;

import org.junit.jupiter.api.Test;

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
}
