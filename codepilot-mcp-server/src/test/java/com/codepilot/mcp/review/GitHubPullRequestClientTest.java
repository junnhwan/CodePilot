package com.codepilot.mcp.review;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubPullRequestClientTest {

    @Test
    void fetchesDiffAndMaterializedFilesFromGitHubApis() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubPullRequestClient client = new GitHubPullRequestClient(builder);

        server.expect(requestTo("https://api.github.com/repos/acme/repo/pulls/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        diff --git a/src/main/java/com/example/UserRepository.java b/src/main/java/com/example/UserRepository.java
                        @@ -1,1 +1,1 @@
                        -old
                        +new
                        """, MediaType.TEXT_PLAIN));
        server.expect(requestTo("https://api.github.com/repos/acme/repo/pulls/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "head": {
                            "sha": "head-sha"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/acme/repo/pulls/42/files?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {
                            "filename": "src/main/java/com/example/UserRepository.java",
                            "status": "modified"
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/repos/acme/repo/contents/src/main/java/com/example/UserRepository.java?ref=head-sha"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "content": "cGFja2FnZSBjb20uZXhhbXBsZTsK",
                          "encoding": "base64"
                        }
                        """, MediaType.APPLICATION_JSON));

        String diff = client.fetchPullRequestDiff("https://api.github.com", "token-123", "acme", "repo", 42);
        String headSha = client.fetchHeadSha("https://api.github.com", "token-123", "acme", "repo", 42);
        List<GitHubPullRequestClient.PullRequestFileSnapshot> files = client.fetchPullRequestFiles(
                "https://api.github.com",
                "token-123",
                "acme",
                "repo",
                42,
                headSha
        );

        assertThat(diff).contains("diff --git");
        assertThat(headSha).isEqualTo("head-sha");
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().path()).isEqualTo("src/main/java/com/example/UserRepository.java");
        assertThat(files.getFirst().content()).contains("package com.example;");
        server.verify();
    }
}
