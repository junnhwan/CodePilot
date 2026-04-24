package com.codepilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class EvalReportWriter {

    private final ObjectMapper objectMapper;

    public EvalReportWriter() {
        this.objectMapper = JsonMapper.builder().findAndAddModules().build();
    }

    public ReportFiles write(Path reportRoot, EvalSuiteResult suiteResult) {
        Path normalizedRoot = reportRoot == null
                ? Path.of("codepilot-eval", "target", "eval-reports").toAbsolutePath().normalize()
                : reportRoot.toAbsolutePath().normalize();
        Path runDirectory = normalizedRoot.resolve(suiteResult.evalRunId()).normalize();

        try {
            Files.createDirectories(runDirectory);
            Path jsonReport = runDirectory.resolve("report.json");
            Path markdownReport = runDirectory.resolve("report.md");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonReport.toFile(), suiteResult);
            Files.writeString(markdownReport, renderMarkdown(suiteResult), StandardCharsets.UTF_8);
            return new ReportFiles(runDirectory, jsonReport, markdownReport);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to write eval report under " + normalizedRoot, error);
        }
    }

    private String renderMarkdown(EvalSuiteResult suiteResult) {
        StringBuilder builder = new StringBuilder();
        builder.append("# CodePilot Eval Report").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- Eval Run: ").append(suiteResult.evalRunId()).append(System.lineSeparator());
        builder.append("- Generated At: ").append(suiteResult.generatedAt()).append(System.lineSeparator());
        builder.append("- Scenario Source: ").append(suiteResult.scenarioSource()).append(System.lineSeparator());
        builder.append("- Primary Baseline: ").append(suiteResult.primaryRun().baseline()).append(System.lineSeparator());
        builder.append(System.lineSeparator());

        builder.append("## Scorecards").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("| Baseline | Model | Passed | Failed | Error | Precision | Recall | F1 | FP Rate | Avg Tokens | Token Efficiency |")
                .append(System.lineSeparator());
        builder.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
                .append(System.lineSeparator());
        for (EvalRunner.RunResult run : suiteResult.runs()) {
            builder.append("| ")
                    .append(run.baseline())
                    .append(" | ")
                    .append(run.model())
                    .append(" | ")
                    .append(run.scorecard().scenariosPassed())
                    .append(" | ")
                    .append(run.scorecard().scenariosFailed())
                    .append(" | ")
                    .append(run.scorecard().scenariosError())
                    .append(" | ")
                    .append(format(run.scorecard().metrics().precision()))
                    .append(" | ")
                    .append(format(run.scorecard().metrics().recall()))
                    .append(" | ")
                    .append(format(run.scorecard().metrics().f1()))
                    .append(" | ")
                    .append(format(run.scorecard().metrics().falsePositiveRate()))
                    .append(" | ")
                    .append(format(run.scorecard().metrics().avgContextTokensUsed()))
                    .append(" | ")
                    .append(format(run.scorecard().metrics().avgTokenEfficiency()))
                    .append(" |")
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());

        if (!suiteResult.baselineComparisons().isEmpty()) {
            builder.append("## Baseline Deltas").append(System.lineSeparator()).append(System.lineSeparator());
            builder.append("| Baseline | Precision Delta | Recall Delta | F1 Delta | FP Delta | Token Efficiency Delta | Passed Delta |")
                    .append(System.lineSeparator());
            builder.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
                    .append(System.lineSeparator());
            for (EvalSuiteResult.BaselineComparison comparison : suiteResult.baselineComparisons()) {
                builder.append("| ")
                        .append(comparison.baseline())
                        .append(" | ")
                        .append(signed(comparison.precisionDelta()))
                        .append(" | ")
                        .append(signed(comparison.recallDelta()))
                        .append(" | ")
                        .append(signed(comparison.f1Delta()))
                        .append(" | ")
                        .append(signed(comparison.falsePositiveRateDelta()))
                        .append(" | ")
                        .append(signed(comparison.tokenEfficiencyDelta()))
                        .append(" | ")
                        .append(comparison.passedDelta())
                        .append(" |")
                        .append(System.lineSeparator());
            }
            builder.append(System.lineSeparator());
        }

        builder.append("## Scenario Matrix").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("| Scenario | Name | Ground Truth | ");
        for (EvalRunner.RunResult run : suiteResult.runs()) {
            builder.append(run.baseline()).append(" | ");
        }
        builder.append(System.lineSeparator());
        builder.append("| --- | --- | ---: | ");
        for (int index = 0; index < suiteResult.runs().size(); index++) {
            builder.append("--- | ");
        }
        builder.append(System.lineSeparator());
        for (EvalSuiteResult.ScenarioSummary scenarioSummary : suiteResult.scenarioSummaries()) {
            builder.append("| ")
                    .append(scenarioSummary.scenarioId())
                    .append(" | ")
                    .append(scenarioSummary.scenarioName())
                    .append(" | ")
                    .append(scenarioSummary.groundTruthCount())
                    .append(" | ");
            for (EvalRunner.RunResult run : suiteResult.runs()) {
                builder.append(renderOutcome(scenarioSummary.outcomes().get(run.baseline()))).append(" | ");
            }
            builder.append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());

        List<String> errors = suiteResult.scenarioSummaries().stream()
                .flatMap(summary -> summary.outcomes().entrySet().stream()
                        .filter(entry -> entry.getValue().status() == EvalSuiteResult.ScenarioOutcome.Status.ERROR)
                        .map(entry -> "- " + entry.getKey() + " / " + summary.scenarioId() + ": " + entry.getValue().errorMessage()))
                .sorted(Comparator.naturalOrder())
                .toList();
        if (!errors.isEmpty()) {
            builder.append("## Errors").append(System.lineSeparator()).append(System.lineSeparator());
            errors.forEach(error -> builder.append(error).append(System.lineSeparator()));
        }

        return builder.toString();
    }

    private String renderOutcome(EvalSuiteResult.ScenarioOutcome outcome) {
        if (outcome == null) {
            return "N/A";
        }
        return switch (outcome.status()) {
            case PASSED -> "PASS";
            case FAILED -> "FAIL (matched=%d missed=%d fp=%d)".formatted(
                    outcome.matchedGroundTruth(),
                    outcome.missedGroundTruth(),
                    outcome.falsePositiveCount()
            );
            case ERROR -> "ERROR";
        };
    }

    private String format(double value) {
        return "%.3f".formatted(value);
    }

    private String signed(double value) {
        return "%+.3f".formatted(value);
    }

    public record ReportFiles(
            Path directory,
            Path jsonReport,
            Path markdownReport
    ) {
    }
}
