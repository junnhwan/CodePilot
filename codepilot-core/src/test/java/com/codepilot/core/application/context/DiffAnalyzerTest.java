package com.codepilot.core.application.context;

import com.codepilot.core.domain.context.DiffSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiffAnalyzerTest {

    private final DiffAnalyzer diffAnalyzer = new DiffAnalyzer();

    @Test
    void analyzesChangedFilesAndHunksFromUnifiedDiff() {
        String rawDiff = """
                diff --git a/src/main/java/com/example/UserService.java b/src/main/java/com/example/UserService.java
                index 1111111..2222222 100644
                --- a/src/main/java/com/example/UserService.java
                +++ b/src/main/java/com/example/UserService.java
                @@ -8,2 +8,4 @@
                     String findByName(String name) {
                -        return legacy(name);
                +        audit(name);
                +        return repository.findByName(name);
                +    }
                 }
                """;

        DiffAnalyzer.DiffAnalysis analysis = diffAnalyzer.analyze(rawDiff);

        assertThat(analysis.rawDiff()).contains("UserService.java");
        assertThat(analysis.fileDeltas()).hasSize(1);
        assertThat(analysis.fileDeltas().getFirst().path()).isEqualTo("src/main/java/com/example/UserService.java");
        assertThat(analysis.fileDeltas().getFirst().changeType()).isEqualTo(DiffSummary.ChangeType.MODIFIED);
        assertThat(analysis.fileDeltas().getFirst().additions()).isEqualTo(3);
        assertThat(analysis.fileDeltas().getFirst().deletions()).isEqualTo(1);
        assertThat(analysis.fileDeltas().getFirst().hunks()).hasSize(1);
        assertThat(analysis.fileDeltas().getFirst().hunks().getFirst().startLine()).isEqualTo(8);
        assertThat(analysis.fileDeltas().getFirst().hunks().getFirst().endLine()).isEqualTo(11);
        assertThat(analysis.fileDeltas().getFirst().hunks().getFirst().content()).contains("repository.findByName(name)");
    }
}
