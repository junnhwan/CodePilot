package com.codepilot.core.infrastructure.tool;

import com.codepilot.core.domain.tool.ToolCall;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AstNavigationToolsTest {

    @TempDir
    Path repoRoot;

    @Test
    void findsTypeAndMethodReferencesAcrossRepository() throws IOException {
        writeJavaFile("src/main/java/com/example/repo/UserRepository.java", """
                package com.example.repo;

                class UserRepository {
                    String findById(String id) {
                        return "user-" + id;
                    }
                }
                """);
        writeJavaFile("src/main/java/com/example/service/UserService.java", """
                package com.example.service;

                import com.example.repo.UserRepository;

                class UserService {

                    private final UserRepository repository;

                    UserService(UserRepository repository) {
                        this.repository = repository;
                    }

                    String loadUser(String id) {
                        return repository.findById(id);
                    }
                }
                """);
        writeJavaFile("src/main/java/com/example/web/UserController.java", """
                package com.example.web;

                import com.example.service.UserService;

                class UserController {

                    private final UserService service;

                    UserController(UserService service) {
                        this.service = service;
                    }

                    String show(String id) {
                        return service.loadUser(id);
                    }
                }
                """);

        AstFindReferencesTool referencesTool = new AstFindReferencesTool(
                repoRoot,
                new JsonMapper(),
                new com.codepilot.core.infrastructure.context.JavaParserAstParser()
        );

        var typeReferences = referencesTool.execute(new ToolCall(
                "call-type-ref",
                "ast_find_references",
                Map.of("symbol", "com.example.repo.UserRepository")
        ));
        var methodReferences = referencesTool.execute(new ToolCall(
                "call-method-ref",
                "ast_find_references",
                Map.of("symbol", "UserRepository#findById")
        ));

        assertThat(typeReferences.success()).isTrue();
        assertThat(typeReferences.output()).contains("UserService").contains("src/main/java/com/example/service/UserService.java");

        assertThat(methodReferences.success()).isTrue();
        assertThat(methodReferences.output()).contains("UserService#loadUser");
    }

    @Test
    void returnsDownstreamCallChainForMethodSymbol() throws IOException {
        writeJavaFile("src/main/java/com/example/repo/UserRepository.java", """
                package com.example.repo;

                class UserRepository {
                    String findById(String id) {
                        return "user-" + id;
                    }
                }
                """);
        writeJavaFile("src/main/java/com/example/service/UserService.java", """
                package com.example.service;

                import com.example.repo.UserRepository;

                class UserService {

                    private final UserRepository repository;

                    UserService(UserRepository repository) {
                        this.repository = repository;
                    }

                    String loadUser(String id) {
                        return repository.findById(id);
                    }
                }
                """);
        writeJavaFile("src/main/java/com/example/web/UserController.java", """
                package com.example.web;

                import com.example.service.UserService;

                class UserController {

                    private final UserService service;

                    UserController(UserService service) {
                        this.service = service;
                    }

                    String show(String id) {
                        return service.loadUser(id);
                    }
                }
                """);

        AstGetCallChainTool callChainTool = new AstGetCallChainTool(
                repoRoot,
                new JsonMapper(),
                new com.codepilot.core.infrastructure.context.JavaParserAstParser()
        );

        var result = callChainTool.execute(new ToolCall(
                "call-chain",
                "ast_get_call_chain",
                Map.of(
                        "symbol", "UserController#show",
                        "direction", "DOWNSTREAM",
                        "max_depth", 3
                )
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("UserController#show -> UserService#loadUser -> UserRepository#findById");
    }

    private void writeJavaFile(String relativePath, String content) throws IOException {
        Path file = repoRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
