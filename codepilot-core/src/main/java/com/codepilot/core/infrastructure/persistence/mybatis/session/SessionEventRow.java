package com.codepilot.core.infrastructure.persistence.mybatis.session;

import java.time.Instant;

public record SessionEventRow(
        Long id,
        String sessionId,
        String eventType,
        String payloadJson,
        Instant occurredAt
) {
}
