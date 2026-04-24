package com.codepilot.core.application.memory;

import com.codepilot.core.application.review.TokenCounter;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.context.ImpactSet;
import com.codepilot.core.domain.memory.ProjectMemory;
import com.codepilot.core.domain.memory.ReviewPattern;
import com.codepilot.core.domain.memory.TeamConvention;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {

    @Test
    void recallsRelevantProjectMemoryAndDropsLowSignalEntries() {
        ProjectMemory source = ProjectMemory.empty("project-alpha")
                .addPattern(new ReviewPattern(
                        "pattern-validation",
                        "project-alpha",
                        ReviewPattern.PatternType.SECURITY_PATTERN,
                        "Validation missing before repository call",
                        "Controllers in this project often skip validation before DAO access.",
                        "repository.findById(request.userId())",
                        4,
                        Instant.parse("2026-04-24T00:00:00Z")
                ))
                .addPattern(new ReviewPattern(
                        "pattern-batch",
                        "project-alpha",
                        ReviewPattern.PatternType.PERF_PATTERN,
                        "Batch repository access for repeated lookups",
                        "Prefer bulk fetch to avoid repeated database round trips in loops.",
                        "repository.findById(id)",
                        2,
                        Instant.parse("2026-04-23T00:00:00Z")
                ))
                .addConvention(new TeamConvention(
                        "conv-gateway",
                        "project-alpha",
                        TeamConvention.Category.ARCHITECTURE,
                        "Gateway should stay thin and delegate validation into core.",
                        "Gateway delegates into a use case after request validation.",
                        "Gateway writes domain state directly.",
                        0.95d,
                        TeamConvention.Source.MANUAL
                ))
                .addConvention(new TeamConvention(
                        "conv-logging",
                        "project-alpha",
                        TeamConvention.Category.FORMAT,
                        "Use Slf4j instead of direct System.out printing.",
                        "log.info(\"created user\")",
                        "System.out.println(userId)",
                        0.80d,
                        TeamConvention.Source.MANUAL
                ));

        DiffSummary diffSummary = DiffSummary.of(List.of(
                new DiffSummary.ChangedFile(
                        "src/main/java/com/example/gateway/UserController.java",
                        DiffSummary.ChangeType.MODIFIED,
                        8,
                        1,
                        List.of("UserController#findUser")
                )
        ));
        ImpactSet impactSet = new ImpactSet(
                Set.of(
                        "src/main/java/com/example/gateway/UserController.java",
                        "src/main/java/com/example/repository/UserRepository.java"
                ),
                Set.of("UserRepository#findById"),
                List.of(List.of("UserController#findUser", "UserRepository#findById"))
        );

        MemoryService memoryService = new MemoryService(new TokenCounter(), 1, 1);
        ProjectMemory recalled = memoryService.recall(
                source,
                diffSummary,
                impactSet,
                """
                diff --git a/src/main/java/com/example/gateway/UserController.java b/src/main/java/com/example/gateway/UserController.java
                @@ -20,0 +21,4 @@
                +    UserDto findUser(UserRequest request) {
                +        return mapper.toDto(repository.findById(request.userId()));
                +    }
                """,
                160
        );

        assertThat(recalled.reviewPatterns())
                .extracting(ReviewPattern::patternId)
                .contains("pattern-validation")
                .doesNotContain("pattern-batch");
        assertThat(recalled.teamConventions())
                .extracting(TeamConvention::conventionId)
                .contains("conv-gateway")
                .doesNotContain("conv-logging");
    }
}
