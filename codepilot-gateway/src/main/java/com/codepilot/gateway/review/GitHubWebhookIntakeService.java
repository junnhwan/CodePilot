package com.codepilot.gateway.review;

import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.gateway.github.GitHubPullRequestEvent;
import com.codepilot.gateway.github.GitHubPullRequestWebhookPayload;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class GitHubWebhookIntakeService {

    private final RedisWebhookDeduplicator deduplicator;

    private final RedisStreamReviewEventBuffer eventBuffer;

    private final ReviewSessionRepository reviewSessionRepository;

    private final ReviewSseBroadcaster sseBroadcaster;

    private final Duration deduplicationTtl;

    public GitHubWebhookIntakeService(
            RedisWebhookDeduplicator deduplicator,
            RedisStreamReviewEventBuffer eventBuffer,
            ReviewSessionRepository reviewSessionRepository,
            ReviewSseBroadcaster sseBroadcaster
    ) {
        this(deduplicator, eventBuffer, reviewSessionRepository, sseBroadcaster, Duration.ofDays(7));
    }

    public GitHubWebhookIntakeService(
            RedisWebhookDeduplicator deduplicator,
            RedisStreamReviewEventBuffer eventBuffer,
            ReviewSessionRepository reviewSessionRepository,
            ReviewSseBroadcaster sseBroadcaster,
            Duration deduplicationTtl
    ) {
        this.deduplicator = deduplicator;
        this.eventBuffer = eventBuffer;
        this.reviewSessionRepository = reviewSessionRepository;
        this.sseBroadcaster = sseBroadcaster;
        this.deduplicationTtl = deduplicationTtl;
    }

    public IntakeResult accept(GitHubPullRequestWebhookPayload payload) {
        if (payload == null || payload.repository() == null || payload.pullRequest() == null || !payload.reviewableAction()) {
            return new IntakeResult(IntakeStatus.IGNORED, null);
        }

        String projectId = payload.repository().fullName();
        String sessionId = "session-" + UUID.randomUUID();
        GitHubPullRequestEvent event = new GitHubPullRequestEvent(
                sessionId,
                projectId,
                payload.repository().owner().login(),
                payload.repository().name(),
                payload.pullRequest().number(),
                payload.pullRequest().htmlUrl(),
                payload.pullRequest().head().sha(),
                payload.pullRequest().base().sha()
        );

        RedisWebhookDeduplicator.ClaimResult claim = deduplicator.claim(
                event.deduplicationKey(),
                sessionId,
                deduplicationTtl
        );
        if (!claim.accepted()) {
            return new IntakeResult(IntakeStatus.DUPLICATE, claim.sessionId());
        }
        String acceptedSessionId = claim.sessionId();
        GitHubPullRequestEvent acceptedEvent = new GitHubPullRequestEvent(
                acceptedSessionId,
                event.projectId(),
                event.owner(),
                event.repository(),
                event.prNumber(),
                event.prUrl(),
                event.headSha(),
                event.baseSha()
        );

        reviewSessionRepository.save(ReviewSession.initialize(
                acceptedSessionId,
                projectId,
                payload.pullRequest().number(),
                payload.pullRequest().htmlUrl(),
                Instant.now()
        ));
        eventBuffer.publish(acceptedEvent);
        sseBroadcaster.publish(acceptedSessionId, "session_created", Map.of(
                "sessionId", acceptedSessionId,
                "prUrl", payload.pullRequest().htmlUrl()
        ));
        return new IntakeResult(IntakeStatus.ACCEPTED, acceptedSessionId);
    }

    public record IntakeResult(
            IntakeStatus status,
            String sessionId
    ) {
    }

    public enum IntakeStatus {
        ACCEPTED,
        DUPLICATE,
        IGNORED
    }
}
