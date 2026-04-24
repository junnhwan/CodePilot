package com.codepilot.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class EvalSuiteRunner {

    private final EvalRunner evalRunner;

    public EvalSuiteRunner(EvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    public EvalSuiteResult run(
            String scenarioSource,
            List<EvalScenario> scenarios,
            List<EvalBaseline> baselines
    ) {
        List<EvalBaseline> selectedBaselines = normalizeBaselines(baselines);
        List<EvalRunner.RunResult> runs = selectedBaselines.stream()
                .map(baseline -> evalRunner.run(scenarios, baseline))
                .toList();
        EvalRunner.RunResult primaryRun = runs.stream()
                .filter(run -> run.baseline() == EvalBaseline.CODEPILOT)
                .findFirst()
                .orElse(runs.getFirst());

        List<EvalSuiteResult.BaselineComparison> comparisons = runs.stream()
                .filter(run -> run.baseline() != primaryRun.baseline())
                .map(run -> comparison(primaryRun, run))
                .toList();

        return new EvalSuiteResult(
                "eval-suite-" + Instant.now().toEpochMilli(),
                Instant.now(),
                scenarioSource == null || scenarioSource.isBlank()
                        ? EvalScenarioLoader.DEFAULT_SCENARIO_PACK
                        : scenarioSource.trim(),
                primaryRun,
                runs,
                comparisons,
                buildScenarioSummaries(scenarios, runs)
        );
    }

    private List<EvalBaseline> normalizeBaselines(List<EvalBaseline> baselines) {
        LinkedHashSet<EvalBaseline> ordered = new LinkedHashSet<>();
        if (baselines == null || baselines.isEmpty()) {
            ordered.add(EvalBaseline.CODEPILOT);
            ordered.add(EvalBaseline.DIRECT_LLM);
            ordered.add(EvalBaseline.FULL_CONTEXT_LLM);
        } else {
            ordered.addAll(baselines);
        }
        return List.copyOf(ordered);
    }

    private EvalSuiteResult.BaselineComparison comparison(
            EvalRunner.RunResult primaryRun,
            EvalRunner.RunResult baselineRun
    ) {
        return new EvalSuiteResult.BaselineComparison(
                baselineRun.baseline(),
                baselineRun.scorecard().metrics().precision() - primaryRun.scorecard().metrics().precision(),
                baselineRun.scorecard().metrics().recall() - primaryRun.scorecard().metrics().recall(),
                baselineRun.scorecard().metrics().f1() - primaryRun.scorecard().metrics().f1(),
                baselineRun.scorecard().metrics().falsePositiveRate() - primaryRun.scorecard().metrics().falsePositiveRate(),
                baselineRun.scorecard().metrics().avgTokenEfficiency() - primaryRun.scorecard().metrics().avgTokenEfficiency(),
                baselineRun.scorecard().scenariosPassed() - primaryRun.scorecard().scenariosPassed()
        );
    }

    private List<EvalSuiteResult.ScenarioSummary> buildScenarioSummaries(
            List<EvalScenario> scenarios,
            List<EvalRunner.RunResult> runs
    ) {
        Map<String, Map<EvalBaseline, EvalSuiteResult.ScenarioOutcome>> outcomesByScenario = new LinkedHashMap<>();
        for (EvalScenario scenario : scenarios == null ? List.<EvalScenario>of() : scenarios) {
            outcomesByScenario.put(scenario.scenarioId(), new LinkedHashMap<>());
        }

        for (EvalRunner.RunResult run : runs) {
            for (EvalRunner.ScenarioResult scenarioResult : run.scenarioResults()) {
                EvalRunner.ScenarioResult.Evaluation evaluation = scenarioResult.evaluation();
                EvalSuiteResult.ScenarioOutcome.Status status = !scenarioResult.successful()
                        ? EvalSuiteResult.ScenarioOutcome.Status.ERROR
                        : scenarioResult.passed()
                        ? EvalSuiteResult.ScenarioOutcome.Status.PASSED
                        : EvalSuiteResult.ScenarioOutcome.Status.FAILED;

                outcomesByScenario.computeIfAbsent(
                        scenarioResult.scenario().scenarioId(),
                        ignored -> new LinkedHashMap<>()
                ).put(
                        run.baseline(),
                        new EvalSuiteResult.ScenarioOutcome(
                                status,
                                evaluation.matchedGroundTruth().size(),
                                evaluation.missedGroundTruth().size(),
                                evaluation.falsePositives().size(),
                                scenarioResult.partial(),
                                scenarioResult.errorMessage()
                        )
                );
            }
        }

        List<EvalSuiteResult.ScenarioSummary> summaries = new ArrayList<>();
        for (EvalScenario scenario : scenarios == null ? List.<EvalScenario>of() : scenarios) {
            summaries.add(new EvalSuiteResult.ScenarioSummary(
                    scenario.scenarioId(),
                    scenario.name(),
                    scenario.groundTruth().size(),
                    outcomesByScenario.getOrDefault(scenario.scenarioId(), Map.of())
            ));
        }
        return List.copyOf(summaries);
    }
}
