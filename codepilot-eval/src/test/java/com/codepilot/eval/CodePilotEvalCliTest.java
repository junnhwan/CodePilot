package com.codepilot.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodePilotEvalCliTest {

    @TempDir
    Path tempDir;

    @Test
    void runsEvalSuiteAndWritesReportsFromCli() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CodePilotEvalCli cli = new CodePilotEvalCli(
                new EvalScenarioLoader(),
                (model, provider) -> new BaselineAwareFixtureLlmClient(),
                new EvalReportWriter(),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );

        int exitCode = cli.run(new String[]{
                "run",
                "--scenario-pack", "eval/scenarios/expanded-scenario-pack.json",
                "--baselines", "codepilot,direct_llm,full_context_llm,lint_only",
                "--report-dir", tempDir.toString()
        });

        assertThat(exitCode).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isBlank();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("CODEPILOT");
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("LINT_ONLY");
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("report.md");

        try (var children = Files.list(tempDir)) {
            Path runDirectory = children.findFirst().orElseThrow();
            assertThat(runDirectory.resolve("report.json")).exists();
            assertThat(runDirectory.resolve("report.md")).exists();
        }
    }
}
