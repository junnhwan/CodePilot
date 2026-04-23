package com.codepilot.gateway.review;

import com.codepilot.gateway.github.GitHubPullRequestEvent;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RedisStreamReviewEventBuffer {

    private final StringRedisTemplate redisTemplate;

    private final String streamKey;

    public RedisStreamReviewEventBuffer(StringRedisTemplate redisTemplate, String streamKey) {
        this.redisTemplate = redisTemplate;
        this.streamKey = streamKey;
    }

    public void publish(GitHubPullRequestEvent event) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("sessionId", event.sessionId());
        payload.put("projectId", event.projectId());
        payload.put("owner", event.owner());
        payload.put("repository", event.repository());
        payload.put("prNumber", Integer.toString(event.prNumber()));
        payload.put("prUrl", event.prUrl());
        payload.put("headSha", event.headSha());
        payload.put("baseSha", event.baseSha());
        redisTemplate.opsForStream().add(MapRecord.create(streamKey, payload));
    }

    public List<BufferedReviewEvent> readPending(int count) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                StreamReadOptions.empty().count(count),
                StreamOffset.fromStart(streamKey)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::toBufferedEvent)
                .toList();
    }

    public void remove(String messageId) {
        redisTemplate.opsForStream().delete(streamKey, RecordId.of(messageId));
    }

    private BufferedReviewEvent toBufferedEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        GitHubPullRequestEvent event = new GitHubPullRequestEvent(
                text(value, "sessionId"),
                text(value, "projectId"),
                text(value, "owner"),
                text(value, "repository"),
                Integer.parseInt(text(value, "prNumber")),
                text(value, "prUrl"),
                text(value, "headSha"),
                text(value, "baseSha")
        );
        return new BufferedReviewEvent(record.getId().getValue(), event);
    }

    private String text(Map<Object, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    public record BufferedReviewEvent(
            String messageId,
            GitHubPullRequestEvent event
    ) {
    }
}
