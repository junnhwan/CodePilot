package com.codepilot.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EvalSuiteResult(
        String evalRunId,
        Instant generatedAt,
        String scenarioSource,
        EvalRunner.RunResult primaryRun,
        List<EvalRunner.RunResult> runs,
        List<BaselineComparison> baselineComparisons,
        List<ScenarioSummary> scenarioSummaries
) {

    public EvalSuiteResult {
        evalRunId = requireText(evalRunId, "evalRunId");
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt must not be null");
        }
        scenarioSource = requireText(scenarioSource, "scenarioSource");
        if (primaryRun == null) {
            throw new IllegalArgumentException("primaryRun must not be null");
        }
        runs = immutableList(runs);
        baselineComparisons = immutableList(baselineComparisons);
        scenarioSummaries = immutableList(scenarioSummaries);
    }

    public EvalRunner.RunResult run(EvalBaseline baseline) {
        return runs.stream()
                .filter(run -> run.baseline() == baseline)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing run for baseline " + baseline));
    }

    public BaselineComparison comparison(EvalBaseline baseline) {
        return baselineComparisons.stream()
                .filter(comparison -> comparison.baseline() == baseline)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing comparison for baseline " + baseline));
    }

    public ScenarioSummary scenarioSummary(String scenarioId) {
        return scenarioSummaries.stream()
                .filter(summary -> summary.scenarioId().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing scenario summary " + scenarioId));
    }

    public record BaselineComparison(
            EvalBaseline baseline,
            double precisionDelta,
            double recallDelta,
            double f1Delta,
            double falsePositiveRateDelta,
            double tokenEfficiencyDelta,
            int passedDelta
    ) {

        public BaselineComparison {
            if (baseline == null) {
                throw new IllegalArgumentException("baseline must not be null");
            }
        }
    }

    public record ScenarioSummary(
            String scenarioId,
            String scenarioName,
            int groundTruthCount,
            Map<EvalBaseline, ScenarioOutcome> outcomes
    ) {

        public ScenarioSummary {
            scenarioId = requireText(scenarioId, "scenarioId");
            scenarioName = requireText(scenarioName, "scenarioName");
            if (groundTruthCount < 0) {
                throw new IllegalArgumentException("groundTruthCount must not be negative");
            }
            outcomes = outcomes == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
        }
    }

    public record ScenarioOutcome(
            Status status,
            int matchedGroundTruth,
            int missedGroundTruth,
            int falsePositiveCount,
            boolean partial,
            String errorMessage
    ) {

        public ScenarioOutcome {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            if (matchedGroundTruth < 0 || missedGroundTruth < 0 || falsePositiveCount < 0) {
                throw new IllegalArgumentException("ScenarioOutcome counters must not be negative");
            }
            errorMessage = errorMessage == null || errorMessage.isBlank() ? null : errorMessage.trim();
        }

        public enum Status {
            PASSED,
            FAILED,
            ERROR
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
