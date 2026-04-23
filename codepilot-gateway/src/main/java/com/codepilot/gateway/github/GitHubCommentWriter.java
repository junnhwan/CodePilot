package com.codepilot.gateway.github;

import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.gateway.review.ReviewMarkdownRenderer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

public class GitHubCommentWriter {

    private final RestClient restClient;

    private final ReviewMarkdownRenderer renderer;

    public GitHubCommentWriter(
            RestClient.Builder restClientBuilder,
            ReviewMarkdownRenderer renderer,
            String apiBaseUrl,
            String apiToken
    ) {
        this.restClient = restClientBuilder
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, apiToken == null || apiToken.isBlank() ? "" : "Bearer " + apiToken)
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        this.renderer = renderer;
    }

    public void writeReview(GitHubPullRequestEvent event, ReviewResult reviewResult) {
        for (Finding finding : reviewResult.findings()) {
            writeInlineComment(event, finding);
        }
        writeSummaryComment(event, reviewResult);
    }

    private void writeInlineComment(GitHubPullRequestEvent event, Finding finding) {
        if (finding.location() == null || finding.location().filePath() == null || finding.location().filePath().isBlank()) {
            return;
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("body", inlineBody(finding));
        request.put("commit_id", event.headSha());
        request.put("path", finding.location().filePath());
        request.put("line", finding.location().endLine());
        request.put("side", "RIGHT");

        restClient.post()
                .uri("/repos/%s/%s/pulls/%d/comments".formatted(event.owner(), event.repository(), event.prNumber()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private void writeSummaryComment(GitHubPullRequestEvent event, ReviewResult reviewResult) {
        restClient.post()
                .uri("/repos/%s/%s/issues/%d/comments".formatted(event.owner(), event.repository(), event.prNumber()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("body", renderer.render(reviewResult)))
                .retrieve()
                .toBodilessEntity();
    }

    private String inlineBody(Finding finding) {
        return """
                Potential finding: %s

                %s

                Suggestion: %s
                Confidence: %.2f
                """.formatted(
                finding.title(),
                finding.description(),
                finding.suggestion(),
                finding.confidence()
        ).trim();
    }
}
