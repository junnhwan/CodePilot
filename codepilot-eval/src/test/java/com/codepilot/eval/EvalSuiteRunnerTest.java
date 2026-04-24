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
                List.of(
                        EvalBaseline.CODEPILOT,
                        EvalBaseline.DIRECT_LLM,
                        EvalBaseline.FULL_CONTEXT_LLM,
                        EvalBaseline.LINT_ONLY
                )
        );

        assertThat(suiteResult.primaryRun().baseline()).isEqualTo(EvalBaseline.CODEPILOT);
        assertThat(suiteResult.runs()).hasSize(4);
        assertThat(suiteResult.primaryRun().scorecard().metrics().f1()).isEqualTo(1.0d);

        EvalRunner.RunResult directRun = suiteResult.run(EvalBaseline.DIRECT_LLM);
        EvalRunner.RunResult fullContextRun = suiteResult.run(EvalBaseline.FULL_CONTEXT_LLM);
        EvalRunner.RunResult lintRun = suiteResult.run(EvalBaseline.LINT_ONLY);

        assertThat(directRun.scorecard().metrics().f1()).isLessThan(suiteResult.primaryRun().scorecard().metrics().f1());
        assertThat(fullContextRun.scorecard().metrics().f1()).isLessThan(suiteResult.primaryRun().scorecard().metrics().f1());
        assertThat(fullContextRun.scorecard().metrics().f1()).isGreaterThan(directRun.scorecard().metrics().f1());
        assertThat(lintRun.scorecard().metrics().recall()).isGreaterThan(directRun.scorecard().metrics().recall());

        EvalSuiteResult.BaselineComparison directComparison = suiteResult.comparison(EvalBaseline.DIRECT_LLM);
        assertThat(directComparison.f1Delta()).isNegative();
        assertThat(directComparison.recallDelta()).isNegative();

        EvalSuiteResult.ScenarioSummary safeScenario = suiteResult.scenarioSummary("eval-safe-refactor-001");
        EvalSuiteResult.ScenarioSummary hardcodedTokenScenario = suiteResult.scenarioSummary("eval-hardcoded-token-001");
        assertThat(safeScenario.outcomes().get(EvalBaseline.CODEPILOT).status())
                .isEqualTo(EvalSuiteResult.ScenarioOutcome.Status.PASSED);
        assertThat(safeScenario.outcomes().get(EvalBaseline.DIRECT_LLM).status())
                .isEqualTo(EvalSuiteResult.ScenarioOutcome.Status.FAILED);
        assertThat(safeScenario.outcomes().get(EvalBaseline.LINT_ONLY).status())
                .isEqualTo(EvalSuiteResult.ScenarioOutcome.Status.PASSED);
        assertThat(hardcodedTokenScenario.outcomes().get(EvalBaseline.LINT_ONLY).status())
                .isEqualTo(EvalSuiteResult.ScenarioOutcome.Status.PASSED);
        assertThat(hardcodedTokenScenario.outcomes().get(EvalBaseline.DIRECT_LLM).status())
                .isEqualTo(EvalSuiteResult.ScenarioOutcome.Status.FAILED);
    }
}
