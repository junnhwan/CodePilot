package com.codepilot.core.domain.review;

import com.codepilot.core.domain.DomainRuleViolationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingTest {

    @Test
    void downgradedFindingIsNotTreatedAsConfirmedIssue() {
        Finding finding = Finding.reported(
                "finding-1",
                "task-security",
                "security",
                Severity.HIGH,
                0.92,
                new Finding.CodeLocation("src/main/java/App.java", 12, 14),
                "Input validation is missing",
                "Untrusted input reaches persistence layer without validation.",
                "Validate request fields before building query arguments.",
                List.of("request body flows into repository call")
        );

        Finding downgraded = finding.downgradeTo(Severity.LOW, "evidence is partial");

        assertThat(downgraded.status()).isEqualTo(Finding.Status.DOWNGRADED);
        assertThat(downgraded.severity()).isEqualTo(Severity.LOW);
        assertThat(downgraded.isIssueConfirmed()).isFalse();
    }

    @Test
    void dismissedFindingCannotBeConfirmedAgain() {
        Finding dismissed = Finding.reported(
                        "finding-2",
                        "task-style",
                        "style",
                        Severity.MEDIUM,
                        0.55,
                        new Finding.CodeLocation("src/main/java/App.java", 20, 20),
                        "Naming suggestion",
                        "Variable name is ambiguous but not clearly harmful.",
                        "Rename the variable if context stays unclear.",
                        List.of("variable x is reused twice")
                )
                .dismiss("not actionable for this change set");

        assertThatThrownBy(dismissed::confirm)
                .isInstanceOf(DomainRuleViolationException.class)
                .hasMessageContaining("finding-2")
                .hasMessageContaining("DISMISSED");
    }
}
