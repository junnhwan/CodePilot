package com.codepilot.gateway.review;

import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.core.infrastructure.persistence.inmemory.InMemoryReviewSessionRepository;
import com.codepilot.gateway.github.GitHubPullRequestEvent;
import com.codepilot.gateway.github.GitHubPullRequestWebhookPayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubWebhookIntakeServiceTest {

    @Test
    void createsSessionPublishesEventAndShortCircuitsDuplicateCommit() {
        RedisWebhookDeduplicator deduplicator = mock(RedisWebhookDeduplicator.class);
        RedisStreamReviewEventBuffer eventBuffer = mock(RedisStreamReviewEventBuffer.class);
        ReviewSessionRepository repository = new InMemoryReviewSessionRepository();
        ReviewSseBroadcaster broadcaster = new ReviewSseBroadcaster();

        when(deduplicator.claim(any(), any(), any()))
                .thenReturn(new RedisWebhookDeduplicator.ClaimResult(true, "session-100"))
                .thenReturn(new RedisWebhookDeduplicator.ClaimResult(false, "session-100"));

        GitHubWebhookIntakeService service = new GitHubWebhookIntakeService(
                deduplicator,
                eventBuffer,
                repository,
                broadcaster,
                Duration.ofDays(7)
        );

        GitHubPullRequestWebhookPayload payload = payload("synchronize");
        GitHubWebhookIntakeService.IntakeResult accepted = service.accept(payload);
        GitHubWebhookIntakeService.IntakeResult duplicate = service.accept(payload);

        assertThat(accepted.status()).isEqualTo(GitHubWebhookIntakeService.IntakeStatus.ACCEPTED);
        assertThat(accepted.sessionId()).isEqualTo("session-100");
        assertThat(repository.findById("session-100")).isPresent();
        assertThat(duplicate.status()).isEqualTo(GitHubWebhookIntakeService.IntakeStatus.DUPLICATE);
        assertThat(duplicate.sessionId()).isEqualTo("session-100");
    }

    private GitHubPullRequestWebhookPayload payload(String action) {
        return new GitHubPullRequestWebhookPayload(
                action,
                new GitHubPullRequestWebhookPayload.Repository(
                        "repo",
                        "acme/repo",
                        new GitHubPullRequestWebhookPayload.Owner("acme")
                ),
                new GitHubPullRequestWebhookPayload.PullRequest(
                        42,
                        "https://github.com/acme/repo/pull/42",
                        new GitHubPullRequestWebhookPayload.Ref("head-sha"),
                        new GitHubPullRequestWebhookPayload.Ref("base-sha")
                )
        );
    }
}
