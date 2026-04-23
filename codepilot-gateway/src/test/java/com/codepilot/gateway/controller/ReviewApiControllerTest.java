package com.codepilot.gateway.controller;

import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.gateway.review.ReviewMarkdownRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewApiControllerTest {

    @Test
    void returnsSessionSummaryAndMarkdownReport() throws Exception {
        ReviewSessionRepository repository = mock(ReviewSessionRepository.class);
        ReviewMarkdownRenderer renderer = mock(ReviewMarkdownRenderer.class);

        ReviewSession reviewSession = new ReviewSession(
                "session-1",
                "acme/repo",
                42,
                "https://github.com/acme/repo/pull/42",
                AgentState.DONE,
                null,
                null,
                new ReviewResult("session-1", java.util.List.of(), false, Instant.parse("2026-04-23T10:00:00Z")),
                java.util.List.of(),
                Instant.parse("2026-04-23T09:55:00Z"),
                Instant.parse("2026-04-23T10:00:00Z")
        );

        when(repository.findById("session-1")).thenReturn(Optional.of(reviewSession));
        when(renderer.render(reviewSession)).thenReturn("# Report");

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ReviewApiController(repository, renderer)).build();

        mockMvc.perform(get("/api/v1/reviews/session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.state").value("DONE"))
                .andExpect(jsonPath("$.projectId").value("acme/repo"));

        mockMvc.perform(get("/api/v1/reviews/session-1/report"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string("# Report"));
    }
}
