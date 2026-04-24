package com.codepilot.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalSuiteRunnerTest {

    @Test
    void comparesCodePilotAgainstConfiguredBaselines() {
        EvalScenarioLoader loader = new EvalScenarioLoader();
        EvalSuiteRunner suiteRunner = new EvalSuiteRunner(new EvalRunner(new BaselineAwareFixtureLlmClient()));

        EvalSuiteResult suiteResult = suiteRunner.run(
                EvalScenarioLoader.DEFAULT_SCENARIO_PACK,
                loader.loadDefaultScenarios(),
                List.of(EvalBaseline.CODEPILOT, EvalBaseline.DIRECT_LLM, EvalBaseline.FULL_CONTEXT_LLM)
        );

        assertThat(suiteResult.primaryRun().baseline()).isEqualTo(EvalBaseline.CODEPILOT);
        assertThat(suiteResult.runs()).hasSize(3);
        assertThat(suiteResult.primaryRun().scorecard().metrics().f1()).isEqualTo(1.0d);

        EvalRunner.RunResult directRun = suiteResult.run(EvalBaseline.DIRECT_LLM);
        EvalRunner.RunResult fullContextRun = suiteResult.run(EvalBaseline.FULL_CONTEXT_LLM);

        assertThat(directRun.scorecard().metrics().f1()).isLessThan(suiteResult.primaryRun().scorecard().metrics().f1());
        assertThat(fullContextRun.scorecard().metrics().f1()).isLessThan(suiteResult.primaryRun().scorecard().metrics().f1());
        assertThat(fullContextRun.scorecard().metrics().f1()).isGreaterThan(directRun.scorecard().metrics().f1());

        EvalSuiteResult.BaselineComparison directComparison = suiteResult.comparison(EvalBaseline.DIRECT_LLM);
        assertThat(directComparison.f1Delta()).isNegative();
        assertThat(directComparison.recallDelta()).isNegative();

        EvalSuiteResult.ScenarioSummary safeScenario = suiteResult.scenarioSummary("eval-safe-refactor-001");
        assertThat(safeScenario.outcomes().get(EvalBaseline.CODEPILOT).status())
                .isEqualTo(EvalSuiteResult.ScenarioOutcome.Status.PASSED);
        assertThat(safeScenario.outcomes().get(EvalBaseline.DIRECT_LLM).status())
                .isEqualTo(EvalSuiteResult.ScenarioOutcome.Status.FAILED);
    }
}
