package com.codepilot.eval;

import com.codepilot.core.domain.llm.LlmClient;
import com.codepilot.core.infrastructure.config.LlmProperties;
import com.codepilot.core.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class CodePilotEvalCli {

    private final EvalScenarioLoader scenarioLoader;

    private final LlmClientFactory llmClientFactory;

    private final EvalReportWriter reportWriter;

    private final PrintStream out;

    private final PrintStream err;

    public CodePilotEvalCli() {
        this(
                new EvalScenarioLoader(),
                CodePilotEvalCli::createLiveLlmClient,
                new EvalReportWriter(),
                System.out,
                System.err
        );
    }

    CodePilotEvalCli(
            EvalScenarioLoader scenarioLoader,
            LlmClientFactory llmClientFactory,
            EvalReportWriter reportWriter,
            PrintStream out,
            PrintStream err
    ) {
        this.scenarioLoader = scenarioLoader;
        this.llmClientFactory = llmClientFactory;
        this.reportWriter = reportWriter;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        int exitCode = new CodePilotEvalCli().run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            LlmClient llmClient = llmClientFactory.create(options.model(), options.provider());
            EvalSuiteRunner suiteRunner = new EvalSuiteRunner(new EvalRunner(
                    llmClient,
                    options.model(),
                    options.provider() == null || options.provider().isBlank()
                            ? Map.of()
                            : Map.of("provider", options.provider())
            ));
            EvalSuiteResult suiteResult = suiteRunner.run(
                    options.scenarioPack(),
                    scenarioLoader.load(options.scenarioPack()),
                    options.baselines()
            );
            EvalReportWriter.ReportFiles reportFiles = reportWriter.write(options.reportDir(), suiteResult);
            printSummary(suiteResult, reportFiles);
            return 0;
        } catch (RuntimeException error) {
            err.println("CodePilot Eval CLI failed: " + error.getMessage());
            return 1;
        }
    }

    private void printSummary(EvalSuiteResult suiteResult, EvalReportWriter.ReportFiles reportFiles) {
        out.println("CodePilot eval completed: " + suiteResult.evalRunId());
        out.println("Reports written to:");
        out.println("- JSON: " + reportFiles.jsonReport());
        out.println("- Markdown: " + reportFiles.markdownReport());
        out.println();
        for (EvalRunner.RunResult run : suiteResult.runs()) {
            out.println("%s | passed=%d failed=%d error=%d | precision=%.3f recall=%.3f f1=%.3f | token_efficiency=%.3f".formatted(
                    run.baseline(),
                    run.scorecard().scenariosPassed(),
                    run.scorecard().scenariosFailed(),
                    run.scorecard().scenariosError(),
                    run.scorecard().metrics().precision(),
                    run.scorecard().metrics().recall(),
                    run.scorecard().metrics().f1(),
                    run.scorecard().metrics().avgTokenEfficiency()
            ));
        }
    }

    private static LlmClient createLiveLlmClient(String model, String providerName) {
        String baseUrl = readRequiredEnv("CODEPILOT_LLM_BASE_URL");
        String apiKey = readRequiredEnv("CODEPILOT_LLM_API_KEY");

        LlmProperties.Provider provider = new LlmProperties.Provider();
        provider.setName(providerName == null || providerName.isBlank() ? "eval" : providerName.trim());
        provider.setBaseUrl(baseUrl);
        provider.setApiKey(apiKey);
        provider.setModels(List.of(model));

        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setDefaultProvider(provider.getName());
        llmProperties.setDefaultModel(model);
        llmProperties.setProviders(List.of(provider));

        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        return new OpenAiCompatibleLlmClient(WebClient.builder(), objectMapper, llmProperties);
    }

    private static String readRequiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + key);
        }
        return value;
    }

    @FunctionalInterface
    interface LlmClientFactory {
        LlmClient create(String model, String provider);
    }

    private record CliOptions(
            String scenarioPack,
            Path reportDir,
            String model,
            String provider,
            List<EvalBaseline> baselines
    ) {

        private static CliOptions parse(String[] args) {
            if (args == null || args.length == 0 || !"run".equalsIgnoreCase(args[0])) {
                throw new IllegalArgumentException("""
                        Usage: run [--scenario-pack <resource-or-file>] [--report-dir <path>] [--model <name>] [--provider <name>] [--baselines <csv>]
                        """.trim());
            }

            String scenarioPack = EvalScenarioLoader.DEFAULT_SCENARIO_PACK;
            Path reportDir = Path.of("codepilot-eval", "target", "eval-reports").toAbsolutePath().normalize();
            String model = "codepilot-eval-review";
            String provider = "eval";
            List<EvalBaseline> baselines = List.of(
                    EvalBaseline.CODEPILOT,
                    EvalBaseline.DIRECT_LLM,
                    EvalBaseline.FULL_CONTEXT_LLM
            );

            for (int index = 1; index < args.length; index++) {
                String arg = args[index];
                if ("--scenario-pack".equals(arg) && index + 1 < args.length) {
                    scenarioPack = args[++index];
                } else if ("--report-dir".equals(arg) && index + 1 < args.length) {
                    reportDir = Path.of(args[++index]).toAbsolutePath().normalize();
                } else if ("--model".equals(arg) && index + 1 < args.length) {
                    model = args[++index];
                } else if ("--provider".equals(arg) && index + 1 < args.length) {
                    provider = args[++index];
                } else if ("--baselines".equals(arg) && index + 1 < args.length) {
                    baselines = Arrays.stream(args[++index].split(","))
                            .map(String::trim)
                            .filter(token -> !token.isBlank())
                            .map(EvalBaseline::parse)
                            .toList();
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument " + arg);
                }
            }

            return new CliOptions(scenarioPack, reportDir, model, provider, baselines);
        }
    }
}
