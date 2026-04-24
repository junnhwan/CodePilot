package com.codepilot.core.application.review;

import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MergeAgent {

    public ReviewResult merge(String sessionId, List<ReviewResult> taskResults) {
        Map<String, Finding> mergedFindings = new LinkedHashMap<>();
        boolean partial = false;

        for (ReviewResult taskResult : taskResults == null ? List.<ReviewResult>of() : taskResults) {
            partial |= taskResult.partial();
            for (Finding finding : taskResult.findings()) {
                if (!keep(finding)) {
                    continue;
                }
                String findingKey = key(finding);
                Finding existing = mergedFindings.get(findingKey);
                mergedFindings.put(findingKey, existing == null ? finding : mergeDuplicate(existing, finding));
            }
        }

        List<Finding> orderedFindings = mergedFindings.values().stream()
                .sorted(Comparator
                        .comparing(Finding::severity)
                        .thenComparing(Finding::confidence, Comparator.reverseOrder())
                        .thenComparing(finding -> finding.location().filePath())
                        .thenComparing(finding -> finding.location().startLine()))
                .toList();

        return new ReviewResult(sessionId, orderedFindings, partial, Instant.now());
    }

    private boolean keep(Finding finding) {
        return finding.severity().ordinal() <= 2 || finding.confidence() >= 0.6d || !finding.evidence().isEmpty();
    }

    private Finding mergeDuplicate(Finding left, Finding right) {
        Finding preferred = prefer(left, right);
        Finding secondary = preferred == left ? right : left;
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        evidence.addAll(preferred.evidence());
        evidence.addAll(secondary.evidence());
        String suggestion = preferred.suggestion().isBlank() ? secondary.suggestion() : preferred.suggestion();

        return Finding.reported(
                preferred.findingId(),
                preferred.taskId(),
                preferred.category(),
                preferred.severity(),
                preferred.confidence(),
                preferred.location(),
                preferred.title(),
                preferred.description(),
                suggestion,
                List.copyOf(evidence)
        );
    }

    private Finding prefer(Finding left, Finding right) {
        if (left.severity().ordinal() != right.severity().ordinal()) {
            return left.severity().ordinal() < right.severity().ordinal() ? left : right;
        }
        if (Double.compare(left.confidence(), right.confidence()) != 0) {
            return left.confidence() >= right.confidence() ? left : right;
        }
        return left;
    }

    private String key(Finding finding) {
        return finding.location().filePath().toLowerCase(Locale.ROOT)
                + ":"
                + finding.location().startLine()
                + ":"
                + finding.location().endLine()
                + ":"
                + finding.title().trim().toLowerCase(Locale.ROOT);
    }
}
