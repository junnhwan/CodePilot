package com.codepilot.core.application.context;

import com.codepilot.core.application.memory.MemoryService;
import com.codepilot.core.application.memory.GlobalKnowledgeService;
import com.codepilot.core.application.review.ReviewPromptTemplates;
import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.agent.AgentDecision;
import com.codepilot.core.domain.agent.AgentDefinition;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.context.CompilationStrategy;
import com.codepilot.core.domain.context.ContextCompiler;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.memory.GlobalKnowledgeEntry;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.infrastructure.context.ClasspathCompilationStrategyLoader;
import com.codepilot.core.infrastructure.context.JavaParserAstParser;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                        .load("java-springboot-maven"),
                new MemoryService(new TokenCounter())
        );

        ProjectMemory projectMemory = ProjectMemory.empty("project-alpha")
                .addPattern(new ReviewPattern(
                        "pattern-1",
                        "project-alpha",
                        ReviewPattern.PatternType.SECURITY_PATTERN,
                        "Validation missing before repository call",
                        "Controllers in this project often skip validation before DAO access.",
                        "repository.findById(request.userId())",
                        3,
                        Instant.parse("2026-04-24T00:00:00Z")
                ))
                .addConvention(new TeamConvention(
                        "conv-1",
                        "project-alpha",
                        TeamConvention.Category.ARCHITECTURE,
                        "Service layer should delegate data access into repository methods.",
                        "UserService calls UserRepository.findByName(name).",
                        "Controllers or callers build queries directly.",
                        0.95d,
                        TeamConvention.Source.MANUAL
                ))
                .addConvention(new TeamConvention(
                        "conv-2",
                        "project-alpha",
                        TeamConvention.Category.FORMAT,
                        "Use Slf4j instead of direct System.out printing.",
                        "log.info(\"created\")",
                        "System.out.println(created)",
                        0.80d,
                        TeamConvention.Source.MANUAL
                ));

        ContextPack contextPack = contextCompiler.compile(
                repoRoot,
                rawDiff,
                projectMemory,
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
        assertThat(contextPack.projectMemory().reviewPatterns())
                .extracting(ReviewPattern::patternId)
                .containsExactly("pattern-1");
        assertThat(contextPack.projectMemory().teamConventions())
                .extracting(TeamConvention::conventionId)
                .contains("conv-1")
                .doesNotContain("conv-2");
        assertThat(contextPack.tokenBudget().totalTokens()).isEqualTo(8000);
        assertThat(contextPack.tokenBudget().reservedTokens()).isEqualTo(500);
    }

    @Test
    void compilesProjectMemoryAndGlobalKnowledgeSeparatelyForSecurityReview() throws IOException {
        Path serviceFile = repoRoot.resolve("src/main/java/com/example/security/ProjectAccessService.java");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, """
                package com.example.security;

                import com.example.repo.ProjectRepository;

                class ProjectAccessService {

                    private final ProjectRepository repository;

                    ProjectAccessService(ProjectRepository repository) {
                        this.repository = repository;
                    }

                    String loadProject(String token) {
                        return repository.loadProjectByToken(token);
                    }
                }
                """);

        Path repositoryFile = repoRoot.resolve("src/main/java/com/example/repo/ProjectRepository.java");
        Files.createDirectories(repositoryFile.getParent());
        Files.writeString(repositoryFile, """
                package com.example.repo;

                class ProjectRepository {
                    String loadProjectByToken(String token) {
                        return token;
                    }
                }
                """);

        String rawDiff = """
                diff --git a/src/main/java/com/example/security/ProjectAccessService.java b/src/main/java/com/example/security/ProjectAccessService.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/security/ProjectAccessService.java
                +++ b/src/main/java/com/example/security/ProjectAccessService.java
                @@ -13,2 +13,2 @@
                     String loadProject(String token) {
                -        tokenGuard.requireProjectAccess(token);
                         return repository.loadProjectByToken(token);
                     }
                """;

        ContextCompiler contextCompiler = new DefaultContextCompiler(
                new DiffAnalyzer(),
                new JavaParserAstParser(),
                new ImpactCalculator(),
                new TokenCounter(),
                new ClasspathCompilationStrategyLoader(JsonMapper.builder().findAndAddModules().build())
                        .load("java-springboot-maven"),
                new MemoryService(new TokenCounter()),
                new GlobalKnowledgeService(globalKnowledgeCatalog())
        );

        ProjectMemory projectMemory = ProjectMemory.empty("project-alpha")
                .addPattern(new ReviewPattern(
                        "pattern-token-guard",
                        "project-alpha",
                        ReviewPattern.PatternType.SECURITY_PATTERN,
                        "Project guard must run before repository access",
                        "This project requires tokenGuard.requireProjectAccess(token) before protected repository calls.",
                        "tokenGuard.requireProjectAccess(token)",
                        5,
                        Instant.parse("2026-04-24T00:00:00Z")
                ));

        ContextPack contextPack = contextCompiler.compile(
                repoRoot,
                rawDiff,
                projectMemory,
                Map.of("entrypoint", "test")
        );

        assertThat(contextPack.projectMemory().reviewPatterns())
                .extracting(ReviewPattern::title)
                .contains("Project guard must run before repository access");
        assertThat(contextPack.globalKnowledge())
                .extracting(GlobalKnowledgeEntry::title)
                .contains("Token guard before repository access");

        AgentDefinition reviewer = new AgentDefinition(
                "security-reviewer",
                "Review security-sensitive repository access",
                Set.of(AgentState.REVIEWING),
                Set.of(AgentDecision.Type.CALL_TOOL, AgentDecision.Type.DELIVER),
                List.of("security", "repository access")
        );
        ReviewTask securityTask = ReviewTask.pending(
                "task-security",
                ReviewTask.TaskType.SECURITY,
                ReviewTask.Priority.HIGH,
                List.of("src/main/java/com/example/security/ProjectAccessService.java"),
                List.of("check token validation before repository access"),
                List.of()
        );
        ReviewTask styleTask = ReviewTask.pending(
                "task-style",
                ReviewTask.TaskType.STYLE,
                ReviewTask.Priority.MEDIUM,
                List.of("src/main/java/com/example/security/ProjectAccessService.java"),
                List.of("check naming and formatting"),
                List.of()
        );

        String securityPrompt = ReviewPromptTemplates.systemMessage(reviewer, securityTask, contextPack, List.of()).content();
        String stylePrompt = ReviewPromptTemplates.systemMessage(reviewer, styleTask, contextPack, List.of()).content();

        assertThat(securityPrompt).contains("Token guard before repository access");
        assertThat(stylePrompt).doesNotContain("Token guard before repository access");
    }

    @Test
    void keepsProjectMemoryBeforeGlobalKnowledgeWhenMemoryBudgetIsTight() throws IOException {
        Path serviceFile = repoRoot.resolve("src/main/java/com/example/security/ProjectAccessService.java");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, """
                package com.example.security;

                import com.example.repo.ProjectRepository;

                class ProjectAccessService {

                    private final ProjectRepository repository;

                    ProjectAccessService(ProjectRepository repository) {
                        this.repository = repository;
                    }

                    String loadProject(String token) {
                        return repository.loadProjectByToken(token);
                    }
                }
                """);

        Path repositoryFile = repoRoot.resolve("src/main/java/com/example/repo/ProjectRepository.java");
        Files.createDirectories(repositoryFile.getParent());
        Files.writeString(repositoryFile, """
                package com.example.repo;

                class ProjectRepository {
                    String loadProjectByToken(String token) {
                        return token;
                    }
                }
                """);

        String rawDiff = """
                diff --git a/src/main/java/com/example/security/ProjectAccessService.java b/src/main/java/com/example/security/ProjectAccessService.java
                @@ -13,2 +13,2 @@
                     String loadProject(String token) {
                -        tokenGuard.requireProjectAccess(token);
                         return repository.loadProjectByToken(token);
                     }
                """;

        CompilationStrategy tightStrategy = new CompilationStrategy(
                "tight-memory-profile",
                "java",
                "spring-boot",
                "maven",
                "javaparser",
                new CompilationStrategy.TokenBudget(320, 20, 30, 40, 160, 24, 30),
                List.of("changed_files", "direct_callees"),
                Map.of("direct_callees", CompilationStrategy.AstMode.METHOD_SIG),
                CompilationStrategy.FallbackStrategy.REGEX_TEXT_ANALYSIS,
                List.of()
        );

        ContextCompiler contextCompiler = new DefaultContextCompiler(
                new DiffAnalyzer(),
                new JavaParserAstParser(),
                new ImpactCalculator(),
                new TokenCounter(),
                tightStrategy,
                new MemoryService(new TokenCounter(), 1, 1),
                new GlobalKnowledgeService(globalKnowledgeCatalog())
        );

        ProjectMemory projectMemory = ProjectMemory.empty("project-alpha")
                .addPattern(new ReviewPattern(
                        "pattern-tight-budget",
                        "project-alpha",
                        ReviewPattern.PatternType.SECURITY_PATTERN,
                        "Token guard rule",
                        "Require tokenGuard before repository access.",
                        "tokenGuard.requireProjectAccess(token)",
                        4,
                        Instant.parse("2026-04-24T00:00:00Z")
                ));

        ContextPack contextPack = contextCompiler.compile(
                repoRoot,
                rawDiff,
                projectMemory,
                Map.of("entrypoint", "tight-budget-test")
        );

        assertThat(contextPack.projectMemory().reviewPatterns())
                .extracting(ReviewPattern::patternId)
                .containsExactly("pattern-tight-budget");
        assertThat(contextPack.globalKnowledge()).isEmpty();
    }

    private static List<GlobalKnowledgeEntry> globalKnowledgeCatalog() {
        return List.of(
                new GlobalKnowledgeEntry(
                        "security-token-001",
                        ReviewTask.TaskType.SECURITY,
                        "Token guard before repository access",
                        "Validate project access tokens before invoking protected repository methods.",
                        List.of("token", "guard", "repository", "access")
                ),
                new GlobalKnowledgeEntry(
                        "security-sql-001",
                        ReviewTask.TaskType.SECURITY,
                        "Parameterized queries required",
                        "Avoid string-built SQL and require prepared statements for repository queries.",
                        List.of("sql", "query", "repository", "jdbc")
                ),
                new GlobalKnowledgeEntry(
                        "perf-loop-001",
                        ReviewTask.TaskType.PERF,
                        "Batch repository lookups in loops",
                        "Avoid repeated repository calls inside loops; prefer bulk reads.",
                        List.of("loop", "repository", "batch")
                )
        );
    }
}
