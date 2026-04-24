package com.codepilot.eval;

import com.codepilot.core.domain.review.Finding;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record Scorecard(
        String evalRunId,
        Instant generatedAt,
        int scenariosTotal,
        int scenariosPassed,
        int scenariosFailed,
        int scenariosError,
        Metrics metrics,
        Map<String, CategoryMetrics> byCategory
) {

    public Scorecard {
        evalRunId = requireText(evalRunId, "evalRunId");
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt must not be null");
        }
        metrics = metrics == null ? Metrics.empty() : metrics;
        byCategory = byCategory == null ? Map.of() : Map.copyOf(byCategory);
    }

    public static Scorecard from(String evalRunId, List<EvalRunner.ScenarioResult> scenarioResults) {
        List<EvalRunner.ScenarioResult> results = scenarioResults == null ? List.of() : List.copyOf(scenarioResults);
        int total = results.size();
        int passed = 0;
        int failed = 0;
        int error = 0;
        int successful = 0;
        int partial = 0;
        int matched = 0;
        int groundTruth = 0;
        int falsePositives = 0;
        long totalDurationMillis = 0L;
        long totalContextTokens = 0L;

        Map<String, Counter> categoryCounters = new LinkedHashMap<>();
        for (EvalRunner.ScenarioResult scenarioResult : results) {
            EvalRunner.ScenarioResult.Evaluation evaluation = scenarioResult.evaluation();
            groundTruth += evaluation.matchedGroundTruth().size() + evaluation.missedGroundTruth().size();
            matched += evaluation.matchedGroundTruth().size();
            falsePositives += evaluation.falsePositives().size();

            if (!scenarioResult.successful()) {
                error++;
            } else if (scenarioResult.passed()) {
                passed++;
            } else {
                failed++;
            }

            if (scenarioResult.successful()) {
                successful++;
                totalDurationMillis += Math.max(scenarioResult.durationMillis(), 0L);
                totalContextTokens += Math.max(scenarioResult.contextTokensUsed(), 0L);
                if (scenarioResult.partial()) {
                    partial++;
                }
            }

            for (EvalScenario.GroundTruthFinding matchedFinding : evaluation.matchedGroundTruth()) {
                counter(categoryCounters, matchedFinding.category()).matched++;
            }
            for (EvalScenario.GroundTruthFinding missedFinding : evaluation.missedGroundTruth()) {
                counter(categoryCounters, missedFinding.category()).missed++;
            }
            for (Finding falsePositiveFinding : evaluation.falsePositives()) {
                counter(categoryCounters, falsePositiveFinding.category()).falsePositives++;
            }
        }

        Map<String, CategoryMetrics> byCategory = new LinkedHashMap<>();
        categoryCounters.forEach((category, counter) -> byCategory.put(
                category,
                new CategoryMetrics(
                        safePrecision(counter.matched, counter.falsePositives),
                        safeRecall(counter.matched, counter.missed),
                        counter.matched,
                        counter.missed,
                        counter.falsePositives
                )
        ));

        Metrics metrics = new Metrics(
                safePrecision(matched, falsePositives),
                safeRecall(matched, groundTruth - matched),
                f1(safePrecision(matched, falsePositives), safeRecall(matched, groundTruth - matched)),
                falsePositiveRate(matched, falsePositives, groundTruth),
                successful == 0 ? 0.0d : (double) totalDurationMillis / successful,
                successful == 0 ? 0.0d : (double) totalContextTokens / successful,
                total == 0 ? 0.0d : (double) partial / total,
                total == 0 ? 0.0d : (double) successful / total
        );

        return new Scorecard(
                evalRunId,
                Instant.now(),
                total,
                passed,
                failed,
                error,
                metrics,
                byCategory
        );
    }

    private static Counter counter(Map<String, Counter> counters, String category) {
        return counters.computeIfAbsent(normalizeCategory(category), ignored -> new Counter());
    }

    private static double safePrecision(int matched, int falsePositives) {
        int reported = matched + falsePositives;
        return reported == 0 ? 1.0d : (double) matched / reported;
    }

    private static double safeRecall(int matched, int missed) {
        int totalGroundTruth = matched + missed;
        return totalGroundTruth == 0 ? 1.0d : (double) matched / totalGroundTruth;
    }

    private static double falsePositiveRate(int matched, int falsePositives, int groundTruth) {
        int reported = matched + falsePositives;
        if (reported == 0) {
            return groundTruth == 0 ? 0.0d : 1.0d;
        }
        return (double) falsePositives / reported;
    }

    private static double f1(double precision, double recall) {
        return precision + recall == 0.0d ? 0.0d : (2.0d * precision * recall) / (precision + recall);
    }

    private static String normalizeCategory(String category) {
        return category == null ? "UNKNOWN" : category.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public record Metrics(
            double precision,
            double recall,
            double f1,
            double falsePositiveRate,
            double avgReviewDurationMillis,
            double avgContextTokensUsed,
            double partialRunRate,
            double endToEndSuccessRate
    ) {

        private static Metrics empty() {
            return new Metrics(0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }
    }

    public record CategoryMetrics(
            double precision,
            double recall,
            int matched,
            int missed,
            int falsePositives
    ) {
    }

    private static final class Counter {
        private int matched;
        private int missed;
        private int falsePositives;
    }
}
