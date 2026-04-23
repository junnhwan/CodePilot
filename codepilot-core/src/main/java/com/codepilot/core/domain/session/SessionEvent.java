package com.codepilot.core.domain.session;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SessionEvent(
        String sessionId,
        Type type,
        Instant occurredAt,
        Map<String, Object> payload
) {

    public SessionEvent {
        sessionId = requireText(sessionId, "sessionId");
        if (type == null) {
            throw new DomainRuleViolationException("SessionEvent[%s] type must not be null".formatted(sessionId));
        }
        if (occurredAt == null) {
            throw new DomainRuleViolationException("SessionEvent[%s] occurredAt must not be null".formatted(sessionId));
        }
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static SessionEvent of(String sessionId, Type type, Instant occurredAt, Map<String, Object> payload) {
        return new SessionEvent(sessionId, type, occurredAt, payload);
    }

    public enum Type {
        SESSION_CREATED,
        SESSION_STATE_CHANGED,
        PLAN_ATTACHED,
        REVIEW_COMPLETED,
        REVIEW_FAILED
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
