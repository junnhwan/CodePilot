package com.codepilot.gateway.controller;

import com.codepilot.gateway.github.GitHubPullRequestWebhookPayload;
import com.codepilot.gateway.github.GitHubWebhookVerifier;
import com.codepilot.gateway.review.GitHubWebhookIntakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhook")
public class WebhookReceiver {

    private final ObjectMapper objectMapper;

    private final GitHubWebhookVerifier webhookVerifier;

    private final GitHubWebhookIntakeService intakeService;

    public WebhookReceiver(
            ObjectMapper objectMapper,
            GitHubWebhookVerifier webhookVerifier,
            GitHubWebhookIntakeService intakeService
    ) {
        this.objectMapper = objectMapper;
        this.webhookVerifier = webhookVerifier;
        this.intakeService = intakeService;
    }

    @PostMapping("/github")
    public ResponseEntity<WebhookResponse> receiveGitHubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String githubEvent,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload
    ) throws Exception {
        if (!"pull_request".equalsIgnoreCase(githubEvent)) {
            return ResponseEntity.ok(new WebhookResponse(GitHubWebhookIntakeService.IntakeStatus.IGNORED.name(), null));
        }
        if (!webhookVerifier.verify(payload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new WebhookResponse("INVALID_SIGNATURE", null));
        }

        GitHubPullRequestWebhookPayload request = objectMapper.readValue(payload, GitHubPullRequestWebhookPayload.class);
        GitHubWebhookIntakeService.IntakeResult result = intakeService.accept(request);
        HttpStatus status = result.status() == GitHubWebhookIntakeService.IntakeStatus.ACCEPTED
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(new WebhookResponse(result.status().name(), result.sessionId()));
    }

    public record WebhookResponse(
            String status,
            String sessionId
    ) {
    }
}
