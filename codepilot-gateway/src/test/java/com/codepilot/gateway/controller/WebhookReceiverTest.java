package com.codepilot.gateway.controller;

import com.codepilot.gateway.github.GitHubPullRequestWebhookPayload;
import com.codepilot.gateway.github.GitHubWebhookVerifier;
import com.codepilot.gateway.review.GitHubWebhookIntakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookReceiverTest {

    @Test
    void acceptsVerifiedPullRequestWebhook() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        GitHubWebhookIntakeService intakeService = mock(GitHubWebhookIntakeService.class);
        when(intakeService.accept(any(GitHubPullRequestWebhookPayload.class)))
                .thenReturn(new GitHubWebhookIntakeService.IntakeResult(
                        GitHubWebhookIntakeService.IntakeStatus.ACCEPTED,
                        "session-100"
                ));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WebhookReceiver(
                objectMapper,
                new GitHubWebhookVerifier("test-secret"),
                intakeService
        )).build();

        String body = """
                {
                  "action": "synchronize",
                  "repository": {
                    "name": "repo",
                    "full_name": "acme/repo",
                    "owner": { "login": "acme" }
                  },
                  "pull_request": {
                    "number": 42,
                    "html_url": "https://github.com/acme/repo/pull/42",
                    "head": { "sha": "head-sha" },
                    "base": { "sha": "base-sha" }
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/webhook/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", signature("test-secret", body))
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.sessionId").value("session-100"));

        verify(intakeService).accept(any(GitHubPullRequestWebhookPayload.class));
    }

    private String signature(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + bytesToHex(digest);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
