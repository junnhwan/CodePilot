package com.codepilot.gateway.controller;

import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.gateway.review.ReviewMarkdownRenderer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewApiController {

    private final ReviewSessionRepository reviewSessionRepository;

    private final ReviewMarkdownRenderer renderer;

    public ReviewApiController(ReviewSessionRepository reviewSessionRepository, ReviewMarkdownRenderer renderer) {
        this.reviewSessionRepository = reviewSessionRepository;
        this.renderer = renderer;
    }

    @GetMapping("/{sessionId}")
    public ReviewSessionResponse getReviewSession(@PathVariable String sessionId) {
        ReviewSession session = loadSession(sessionId);
        return new ReviewSessionResponse(
                session.sessionId(),
                session.projectId(),
                session.prNumber(),
                session.prUrl(),
                session.state().name(),
                session.reviewResult() != null && session.reviewResult().partial(),
                session.reviewResult() == null ? 0 : session.reviewResult().findings().size(),
                session.createdAt(),
                session.completedAt()
        );
    }

    @GetMapping(value = "/{sessionId}/report", produces = "text/markdown")
    public ResponseEntity<String> getReviewReport(@PathVariable String sessionId) {
        ReviewSession session = loadSession(sessionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown"))
                .body(renderer.render(session));
    }

    private ReviewSession loadSession(String sessionId) {
        return reviewSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "ReviewSession %s not found".formatted(sessionId)));
    }

    public record ReviewSessionResponse(
            String sessionId,
            String projectId,
            Integer prNumber,
            String prUrl,
            String state,
            boolean partial,
            int findingCount,
            java.time.Instant createdAt,
            java.time.Instant completedAt
    ) {
    }
}
