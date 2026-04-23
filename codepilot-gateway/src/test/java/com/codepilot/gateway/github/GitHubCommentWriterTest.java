package com.codepilot.gateway.github;

import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import com.codepilot.gateway.review.ReviewMarkdownRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubCommentWriterTest {

    @Test
    void postsSummaryAndInlineCommentBodies() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubCommentWriter writer = new GitHubCommentWriter(
                builder,
                new ReviewMarkdownRenderer(),
                "https://api.github.com",
                "token-123"
        );

        GitHubPullRequestEvent event = new GitHubPullRequestEvent(
                "session-1",
                "acme/repo",
                "acme",
                "repo",
                42,
                "https://github.com/acme/repo/pull/42",
                "head-sha",
                "base-sha"
        );
        ReviewResult reviewResult = new ReviewResult(
                "session-1",
                List.of(Finding.reported(
                        "finding-1",
                        "task-security",
                        "security",
                        Severity.HIGH,
                        0.93d,
                        new Finding.CodeLocation("src/main/java/com/example/UserRepository.java", 18, 18),
                        "Potential SQL injection risk",
                        "The query string concatenates user input.",
                        "Use parameterized SQL.",
                        List.of("name is concatenated into SQL")
                )),
                false,
                Instant.parse("2026-04-23T10:00:00Z")
        );

        server.expect(requestTo("https://api.github.com/repos/acme/repo/pulls/42/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/acme/repo/issues/42/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Potential finding")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        writer.writeReview(event, reviewResult);

        server.verify();
    }
}
