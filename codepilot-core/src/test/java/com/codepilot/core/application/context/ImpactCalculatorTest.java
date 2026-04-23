package com.codepilot.core.application.context;

import com.codepilot.core.domain.context.AstParser;
import com.codepilot.core.domain.context.CompilationStrategy;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactCalculatorTest {

    @TempDir
    Path repoRoot;

    private final DiffAnalyzer diffAnalyzer = new DiffAnalyzer();

    private final AstParser astParser = new JavaParserAstParser();

    private final ImpactCalculator impactCalculator = new ImpactCalculator();

    @Test
    void expandsImpactToTouchedSymbolsAndDirectDependencies() throws IOException {
        Path serviceFile = repoRoot.resolve("src/main/java/com/example/service/UserService.java");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, """
                package com.example.service;

                import com.example.repo.UserRepository;

                class UserService {

                    private final UserRepository repository;

                    UserService(UserRepository repository) {
                        this.repository = repository;
                    }

                    String findByName(String name) {
                        return repository.findByName(name);
                    }
                }
                """);

        Path repositoryFile = repoRoot.resolve("src/main/java/com/example/repo/UserRepository.java");
        Files.createDirectories(repositoryFile.getParent());
        Files.writeString(repositoryFile, """
                package com.example.repo;

                class UserRepository {
                    String findByName(String name) {
                        return name;
                    }
                }
                """);

        String rawDiff = """
                diff --git a/src/main/java/com/example/service/UserService.java b/src/main/java/com/example/service/UserService.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/service/UserService.java
                +++ b/src/main/java/com/example/service/UserService.java
                @@ -13,2 +13,2 @@
                     String findByName(String name) {
                -        return legacy(name);
                +        return repository.findByName(name);
                     }
                """;

        DiffAnalyzer.DiffAnalysis diffAnalysis = diffAnalyzer.analyze(rawDiff);
        AstParser.ParsedSourceFile parsedChangedFile = astParser.parse(repoRoot, "src/main/java/com/example/service/UserService.java");

        ImpactCalculator.ImpactAnalysis impactAnalysis = impactCalculator.calculate(
                repoRoot,
                diffAnalysis,
                Map.of(parsedChangedFile.filePath(), parsedChangedFile),
                astParser,
                minimalStrategy()
        );

        assertThat(impactAnalysis.diffSummary().changedFiles()).hasSize(1);
        assertThat(impactAnalysis.diffSummary().changedFiles().getFirst().touchedSymbols())
                .contains("UserService#findByName");
        assertThat(impactAnalysis.impactSet().impactedFiles())
                .contains("src/main/java/com/example/service/UserService.java", "src/main/java/com/example/repo/UserRepository.java");
        assertThat(impactAnalysis.impactSet().callChains())
                .contains(java.util.List.of("UserService#findByName", "UserRepository"));
        assertThat(impactAnalysis.dependencyFiles()).containsKey("src/main/java/com/example/repo/UserRepository.java");
    }

    private CompilationStrategy minimalStrategy() {
        return new CompilationStrategy(
                "java-springboot-maven",
                "java",
                "spring-boot",
                "maven",
                "javaparser",
                new CompilationStrategy.TokenBudget(8000, 500, 1000, 500, 4000, 1500, 500),
                java.util.List.of("changed_files", "direct_callees"),
                java.util.Map.of(
                        "changed_files", CompilationStrategy.AstMode.FULL,
                        "direct_callees", CompilationStrategy.AstMode.METHOD_SIG
                ),
                CompilationStrategy.FallbackStrategy.REGEX_TEXT_ANALYSIS,
                java.util.List.of("**/target/**")
        );
    }
}
