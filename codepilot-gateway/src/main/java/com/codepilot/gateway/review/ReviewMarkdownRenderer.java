package com.codepilot.gateway.review;

import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.session.ReviewSession;

public class ReviewMarkdownRenderer {

    public String render(ReviewSession reviewSession) {
        return render(reviewSession.reviewResult());
    }

    public String render(ReviewResult reviewResult) {
        if (reviewResult == null) {
            return "# CodePilot Review\n\nReview has not produced a result yet.";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("# CodePilot Review").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("> Findings below are reported by the current reviewer run and are not issue-confirmed.")
                .append(System.lineSeparator()).append(System.lineSeparator());

        if (reviewResult.findings().isEmpty()) {
            builder.append("No high-signal findings were reported in this run.");
            return builder.toString();
        }

        if (reviewResult.partial()) {
            builder.append("Partial review: the agent stopped before completing the full loop.")
                    .append(System.lineSeparator()).append(System.lineSeparator());
        }

        for (Finding finding : reviewResult.findings()) {
            builder.append("## Potential finding: ").append(finding.title()).append(System.lineSeparator()).append(System.lineSeparator());
            builder.append("- Severity: ").append(finding.severity()).append(System.lineSeparator());
            builder.append("- Confidence: ").append(String.format("%.2f", finding.confidence())).append(System.lineSeparator());
            builder.append("- Location: ").append(finding.location().filePath())
                    .append(":")
                    .append(finding.location().startLine())
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
            builder.append(finding.description()).append(System.lineSeparator()).append(System.lineSeparator());
            if (finding.suggestion() != null && !finding.suggestion().isBlank()) {
                builder.append("Suggestion: ").append(finding.suggestion()).append(System.lineSeparator()).append(System.lineSeparator());
            }
        }
        return builder.toString().trim();
    }
}
