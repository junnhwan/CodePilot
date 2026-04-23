package com.codepilot.core.infrastructure.persistence.inmemory;

import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.core.domain.session.SessionEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryReviewSessionRepository implements ReviewSessionRepository {

    private final Map<String, ReviewSession> sessions = new ConcurrentHashMap<>();

    private final Map<String, List<SessionEvent>> events = new ConcurrentHashMap<>();

    @Override
    public Optional<ReviewSession> findById(String sessionId) {
        ReviewSession reviewSession = sessions.get(sessionId);
        if (reviewSession == null) {
            return Optional.empty();
        }
        return Optional.of(withEvents(reviewSession, findEvents(sessionId)));
    }

    @Override
    public void save(ReviewSession reviewSession) {
        List<SessionEvent> mergedEvents = mergeEvents(reviewSession.sessionId(), reviewSession.events());
        events.put(reviewSession.sessionId(), mergedEvents);
        sessions.put(reviewSession.sessionId(), withEvents(reviewSession, mergedEvents));
    }

    @Override
    public void append(SessionEvent sessionEvent) {
        List<SessionEvent> mergedEvents = mergeEvents(sessionEvent.sessionId(), List.of(sessionEvent));
        events.put(sessionEvent.sessionId(), mergedEvents);
        ReviewSession current = sessions.get(sessionEvent.sessionId());
        if (current != null) {
            sessions.put(sessionEvent.sessionId(), withEvents(current, mergedEvents));
        }
    }

    @Override
    public List<SessionEvent> findEvents(String sessionId) {
        return events.getOrDefault(sessionId, List.of()).stream()
                .sorted(Comparator.comparing(SessionEvent::occurredAt))
                .toList();
    }

    private List<SessionEvent> mergeEvents(String sessionId, List<SessionEvent> incoming) {
        LinkedHashMap<String, SessionEvent> merged = new LinkedHashMap<>();
        for (SessionEvent event : events.getOrDefault(sessionId, List.of())) {
            merged.put(eventKey(event), event);
        }
        for (SessionEvent event : incoming) {
            merged.put(eventKey(event), event);
        }
        return List.copyOf(merged.values());
    }

    private ReviewSession withEvents(ReviewSession reviewSession, List<SessionEvent> mergedEvents) {
        return new ReviewSession(
                reviewSession.sessionId(),
                reviewSession.projectId(),
                reviewSession.prNumber(),
                reviewSession.prUrl(),
                reviewSession.state(),
                reviewSession.diffSummary(),
                reviewSession.reviewPlan(),
                reviewSession.reviewResult(),
                new ArrayList<>(mergedEvents),
                reviewSession.createdAt(),
                reviewSession.completedAt()
        );
    }

    private String eventKey(SessionEvent sessionEvent) {
        return sessionEvent.sessionId()
                + "|"
                + sessionEvent.type().name()
                + "|"
                + sessionEvent.occurredAt()
                + "|"
                + sessionEvent.payload().toString();
    }
}
