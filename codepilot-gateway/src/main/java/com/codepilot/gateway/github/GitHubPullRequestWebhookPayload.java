package com.codepilot.gateway.github;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Set;

public record GitHubPullRequestWebhookPayload(
        String action,
        Repository repository,
        @JsonProperty("pull_request")
        PullRequest pullRequest
) {

    private static final Set<String> REVIEWABLE_ACTIONS = Set.of("opened", "reopened", "synchronize");

    public boolean reviewableAction() {
        return REVIEWABLE_ACTIONS.contains(action);
    }

    public record Repository(
            String name,
            @JsonProperty("full_name")
            String fullName,
            Owner owner
    ) {
    }

    public record Owner(String login) {
    }

    public record PullRequest(
            int number,
            @JsonProperty("html_url")
            String htmlUrl,
            Ref head,
            Ref base
    ) {
    }

    public record Ref(String sha) {
    }
}
