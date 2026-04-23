package com.codepilot.core.application.context;

import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultContextCompilerTest {

    @TempDir
    Path repoRoot;

    @Test
    void compilesContextPackWithChangedFileContentAndDirectDependencySymbols() throws IOException {
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

        ContextCompiler contextCompiler = new DefaultContextCompiler(
                new DiffAnalyzer(),
                new JavaParserAstParser(),
                new ImpactCalculator(),
                new TokenCounter(),
                new ClasspathCompilationStrategyLoader(JsonMapper.builder().findAndAddModules().build())
                        .load("java-springboot-maven")
        );

        ContextPack contextPack = contextCompiler.compile(
                repoRoot,
                rawDiff,
                ProjectMemory.empty("project-alpha"),
                Map.of("entrypoint", "test")
        );

        assertThat(contextPack.structuredFacts())
                .containsEntry("profileId", "java-springboot-maven")
                .containsEntry("language", "java")
                .containsEntry("entrypoint", "test");
        assertThat(contextPack.diffSummary().changedFiles().getFirst().touchedSymbols()).contains("UserService#findByName");
        assertThat(contextPack.impactSet().impactedFiles())
                .contains("src/main/java/com/example/service/UserService.java", "src/main/java/com/example/repo/UserRepository.java");
        assertThat(contextPack.snippets())
                .anySatisfy(snippet -> {
                    assertThat(snippet.filePath()).isEqualTo("src/main/java/com/example/service/UserService.java");
                    assertThat(snippet.reason()).contains("Changed file content");
                    assertThat(snippet.content()).contains("return repository.findByName(name);");
                })
                .anySatisfy(snippet -> {
                    assertThat(snippet.filePath()).isEqualTo("src/main/java/com/example/repo/UserRepository.java");
                    assertThat(snippet.reason()).contains("Direct dependency symbols");
                    assertThat(snippet.content()).contains("UserRepository#findByName");
                });
        assertThat(contextPack.tokenBudget().totalTokens()).isEqualTo(8000);
        assertThat(contextPack.tokenBudget().reservedTokens()).isEqualTo(500);
    }
}
