package com.codepilot.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesStableJsonAndMarkdownReports() throws IOException {
        EvalScenarioLoader loader = new EvalScenarioLoader();
        EvalSuiteRunner suiteRunner = new EvalSuiteRunner(new EvalRunner(new BaselineAwareFixtureLlmClient()));
        EvalSuiteResult suiteResult = suiteRunner.run(
                EvalScenarioLoader.DEFAULT_SCENARIO_PACK,
                loader.loadDefaultScenarios(),
                List.of(EvalBaseline.CODEPILOT, EvalBaseline.DIRECT_LLM, EvalBaseline.FULL_CONTEXT_LLM)
        );

        EvalReportWriter.ReportFiles reportFiles = new EvalReportWriter().write(tempDir, suiteResult);

        assertThat(reportFiles.jsonReport()).exists();
        assertThat(reportFiles.markdownReport()).exists();

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        var jsonReport = objectMapper.readTree(reportFiles.jsonReport().toFile());
        assertThat(jsonReport.path("evalRunId").asText()).isEqualTo(suiteResult.evalRunId());
        assertThat(jsonReport.path("runs")).hasSize(3);
        assertThat(jsonReport.path("baselineComparisons").isArray()).isTrue();
        assertThat(jsonReport.path("scenarioSummaries").isArray()).isTrue();

        String markdown = Files.readString(reportFiles.markdownReport());
        assertThat(markdown).contains("CodePilot Eval Report");
        assertThat(markdown).contains("CODEPILOT");
        assertThat(markdown).contains("DIRECT_LLM");
        assertThat(markdown).contains("eval-safe-refactor-001");
    }
}
