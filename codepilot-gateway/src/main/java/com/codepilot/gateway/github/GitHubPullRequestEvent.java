package com.codepilot.gateway.github;

public record GitHubPullRequestEvent(
        String sessionId,
        String projectId,
        String owner,
        String repository,
        int prNumber,
        String prUrl,
        String headSha,
        String baseSha
) {

    public String deduplicationKey() {
        return "codepilot:github:dedupe:%s:%d:%s".formatted(projectId, prNumber, headSha);
    }
}
