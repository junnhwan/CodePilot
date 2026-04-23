package com.codepilot.gateway.review;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

public class RedisWebhookDeduplicator {

    private final StringRedisTemplate redisTemplate;

    public RedisWebhookDeduplicator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public ClaimResult claim(String key, String sessionId, Duration ttl) {
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, sessionId, ttl);
        if (Boolean.TRUE.equals(claimed)) {
            return new ClaimResult(true, sessionId);
        }
        String existingSessionId = redisTemplate.opsForValue().get(key);
        return new ClaimResult(false, existingSessionId == null ? sessionId : existingSessionId);
    }

    public record ClaimResult(
            boolean accepted,
            String sessionId
    ) {
    }
}
