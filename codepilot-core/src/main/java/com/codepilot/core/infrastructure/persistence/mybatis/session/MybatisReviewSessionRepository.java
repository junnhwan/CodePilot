package com.codepilot.core.infrastructure.persistence.mybatis.session;

import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.core.domain.session.SessionEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class MybatisReviewSessionRepository implements ReviewSessionRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ReviewSessionMapper reviewSessionMapper;
    private final SessionEventMapper sessionEventMapper;
    private final ObjectMapper objectMapper;

    public MybatisReviewSessionRepository(
            ReviewSessionMapper reviewSessionMapper,
            SessionEventMapper sessionEventMapper,
            ObjectMapper objectMapper
    ) {
        this.reviewSessionMapper = reviewSessionMapper;
        this.sessionEventMapper = sessionEventMapper;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Optional<ReviewSession> findById(String sessionId) {
        ReviewSessionRow row = reviewSessionMapper.selectById(sessionId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(toDomain(row, findEvents(sessionId)));
    }

    @Override
    public void save(ReviewSession reviewSession) {
        List<SessionEvent> mergedEvents = mergeEvents(reviewSession.sessionId(), reviewSession.events());
        reviewSessionMapper.upsert(toRow(reviewSession));
        sessionEventMapper.deleteBySessionId(reviewSession.sessionId());
        for (SessionEvent event : mergedEvents) {
            sessionEventMapper.insert(toRow(event));
        }
    }

    @Override
    public void append(SessionEvent sessionEvent) {
        sessionEventMapper.insert(toRow(sessionEvent));
    }

    @Override
    public List<SessionEvent> findEvents(String sessionId) {
        return sessionEventMapper.selectBySessionId(sessionId).stream()
                .map(this::toDomain)
                .toList();
    }

    private ReviewSessionRow toRow(ReviewSession reviewSession) {
        return new ReviewSessionRow(
                reviewSession.sessionId(),
                reviewSession.projectId(),
                reviewSession.prNumber(),
                reviewSession.prUrl(),
                reviewSession.state().name(),
                writeJson("sessionId=%s diffSummary".formatted(reviewSession.sessionId()), reviewSession.diffSummary()),
                writeJson("sessionId=%s reviewPlan".formatted(reviewSession.sessionId()), reviewSession.reviewPlan()),
                writeJson("sessionId=%s reviewResult".formatted(reviewSession.sessionId()), reviewSession.reviewResult()),
                reviewSession.createdAt(),
                reviewSession.completedAt()
        );
    }

    private ReviewSession toDomain(ReviewSessionRow row, List<SessionEvent> events) {
        return new ReviewSession(
                row.sessionId(),
                row.projectId(),
                row.prNumber(),
                row.prUrl(),
                AgentState.valueOf(row.state()),
                readJson("sessionId=%s diffSummary".formatted(row.sessionId()), row.diffSummaryJson(), DiffSummary.class),
                readJson("sessionId=%s reviewPlan".formatted(row.sessionId()), row.reviewPlanJson(), ReviewPlan.class),
                readJson("sessionId=%s reviewResult".formatted(row.sessionId()), row.reviewResultJson(), ReviewResult.class),
                events,
                row.createdAt(),
                row.completedAt()
        );
    }

    private SessionEventRow toRow(SessionEvent sessionEvent) {
        return new SessionEventRow(
                null,
                sessionEvent.sessionId(),
                sessionEvent.type().name(),
                writeJson("sessionId=%s sessionEvent".formatted(sessionEvent.sessionId()), sessionEvent.payload()),
                sessionEvent.occurredAt()
        );
    }

    private SessionEvent toDomain(SessionEventRow sessionEventRow) {
        return new SessionEvent(
                sessionEventRow.sessionId(),
                SessionEvent.Type.valueOf(sessionEventRow.eventType()),
                sessionEventRow.occurredAt(),
                readJson("sessionId=%s sessionEvent".formatted(sessionEventRow.sessionId()), sessionEventRow.payloadJson(), MAP_TYPE)
        );
    }

    private String writeJson(String context, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize %s".formatted(context), exception);
        }
    }

    private <T> T readJson(String context, String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize %s".formatted(context), exception);
        }
    }

    private <T> T readJson(String context, String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize %s".formatted(context), exception);
        }
    }

    private List<SessionEvent> mergeEvents(String sessionId, List<SessionEvent> sessionEvents) {
        LinkedHashMap<String, SessionEvent> merged = new LinkedHashMap<>();
        for (SessionEvent event : findEvents(sessionId)) {
            merged.put(eventKey(event), event);
        }
        for (SessionEvent event : sessionEvents) {
            merged.put(eventKey(event), event);
        }
        return List.copyOf(merged.values());
    }

    private String eventKey(SessionEvent sessionEvent) {
        return sessionEvent.sessionId()
                + "|"
                + sessionEvent.type().name()
                + "|"
                + sessionEvent.occurredAt()
                + "|"
                + writeJson("sessionId=%s sessionEvent".formatted(sessionEvent.sessionId()), sessionEvent.payload());
    }
}
