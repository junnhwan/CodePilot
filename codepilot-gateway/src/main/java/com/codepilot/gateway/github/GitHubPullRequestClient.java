package com.codepilot.gateway.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class GitHubPullRequestClient {

    private final RestClient restClient;

    public GitHubPullRequestClient(RestClient.Builder restClientBuilder, String apiBaseUrl, String apiToken) {
        this.restClient = restClientBuilder
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, bearer(apiToken))
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    public String fetchPullRequestDiff(String owner, String repository, int prNumber) {
        return restClient.get()
                .uri("/repos/%s/%s/pulls/%d".formatted(owner, repository, prNumber))
                .header(HttpHeaders.ACCEPT, "application/vnd.github.v3.diff")
                .retrieve()
                .body(String.class);
    }

    public List<PullRequestFileSnapshot> fetchPullRequestFiles(String owner, String repository, int prNumber, String headSha) {
        List<PullRequestFileSnapshot> snapshots = new ArrayList<>();
        for (int page = 1; ; page++) {
            PullRequestFileResponse[] pageItems = restClient.get()
                    .uri("/repos/%s/%s/pulls/%d/files?per_page=100&page=%d".formatted(owner, repository, prNumber, page))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(PullRequestFileResponse[].class);
            if (pageItems == null || pageItems.length == 0) {
                break;
            }
            for (PullRequestFileResponse item : pageItems) {
                String content = "removed".equalsIgnoreCase(item.status())
                        ? ""
                        : fetchFileContent(owner, repository, item.filename(), headSha);
                snapshots.add(new PullRequestFileSnapshot(item.filename(), item.status(), content));
            }
            if (pageItems.length < 100) {
                break;
            }
        }
        return List.copyOf(snapshots);
    }

    private String fetchFileContent(String owner, String repository, String path, String headSha) {
        ContentResponse response = restClient.get()
                .uri("/repos/%s/%s/contents/%s?ref=%s".formatted(
                        owner,
                        repository,
                        UriUtils.encodePath(path, StandardCharsets.UTF_8),
                        UriUtils.encodeQueryParam(headSha, StandardCharsets.UTF_8)
                ))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(ContentResponse.class);
        if (response == null || !StringUtils.hasText(response.content())) {
            return "";
        }
        String raw = response.content().replaceAll("\\s+", "");
        if (!"base64".equalsIgnoreCase(response.encoding())) {
            throw new IllegalStateException("Unsupported GitHub content encoding %s for %s".formatted(response.encoding(), path));
        }
        return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
    }

    private static String bearer(String apiToken) {
        return apiToken == null || apiToken.isBlank() ? "" : "Bearer " + apiToken;
    }

    public record PullRequestFileSnapshot(
            String path,
            String status,
            String content
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PullRequestFileResponse(
            String filename,
            String status
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContentResponse(
            String content,
            String encoding
    ) {
    }
}
