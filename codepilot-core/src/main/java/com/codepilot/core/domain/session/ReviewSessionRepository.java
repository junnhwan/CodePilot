package com.codepilot.core.domain.session;

import java.util.List;
import java.util.Optional;

public interface ReviewSessionRepository {

    Optional<ReviewSession> findById(String sessionId);

    void save(ReviewSession reviewSession);

    void append(SessionEvent sessionEvent);

    List<SessionEvent> findEvents(String sessionId);
}
