package com.codepilot.core.infrastructure.context;

import com.codepilot.core.domain.context.AstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JavaParserAstParserTest {

    @TempDir
    Path repoRoot;

    private final AstParser astParser = new JavaParserAstParser();

    @Test
    void parsesTypeMethodAndDependencySymbolsFromJavaSource() throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/service/UserService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
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

        AstParser.ParsedSourceFile parsed = astParser.parse(repoRoot, "src/main/java/com/example/service/UserService.java");

        assertThat(parsed.parseMode()).isEqualTo(AstParser.ParseMode.FULL_AST);
        assertThat(parsed.imports()).contains("com.example.repo.UserRepository");
        assertThat(parsed.types()).extracting(AstParser.TypeSymbol::simpleName).contains("UserService");
        assertThat(parsed.allMethodSymbols()).extracting(AstParser.MethodSymbol::symbolName).contains("UserService#findByName");
        assertThat(parsed.referencedTypes()).contains("com.example.repo.UserRepository");
    }

    @Test
    void fallsBackToRegexExtractionWhenJavaParserCannotParseFile() throws IOException {
        Path sourceFile = repoRoot.resolve("src/main/java/com/example/BrokenService.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.example;

                class BrokenService {
                    String brokenMethod( {
                        return "oops";
                    }
                }
                """);

        AstParser.ParsedSourceFile parsed = astParser.parse(repoRoot, "src/main/java/com/example/BrokenService.java");

        assertThat(parsed.parseMode()).isEqualTo(AstParser.ParseMode.REGEX_FALLBACK);
        assertThat(parsed.types()).extracting(AstParser.TypeSymbol::simpleName).contains("BrokenService");
        assertThat(parsed.allMethodSymbols()).extracting(AstParser.MethodSymbol::symbolName).contains("BrokenService#brokenMethod");
    }
}
