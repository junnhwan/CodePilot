package com.codepilot.gateway.review;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ReviewSseBroadcaster {

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private final Map<String, List<ReviewStreamEvent>> backlogs = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        for (ReviewStreamEvent event : backlogs.getOrDefault(sessionId, List.of())) {
            send(sessionId, emitter, event);
        }
        return emitter;
    }

    public void publish(String sessionId, String eventName, Object payload) {
        ReviewStreamEvent event = new ReviewStreamEvent(eventName, payload);
        backlogs.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(event);
        List<ReviewStreamEvent> backlog = backlogs.get(sessionId);
        if (backlog != null && backlog.size() > 32) {
            backlog.remove(0);
        }
        for (SseEmitter emitter : emitters.getOrDefault(sessionId, List.of())) {
            send(sessionId, emitter, event);
        }
    }

    public void complete(String sessionId) {
        for (SseEmitter emitter : emitters.getOrDefault(sessionId, List.of())) {
            emitter.complete();
        }
        emitters.remove(sessionId);
    }

    private void send(String sessionId, SseEmitter emitter, ReviewStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.event()).data(event.payload()));
        } catch (IOException error) {
            removeEmitter(sessionId, emitter);
            emitter.completeWithError(error);
        }
    }

    private void removeEmitter(String sessionId, SseEmitter emitter) {
        List<SseEmitter> sessionEmitters = emitters.get(sessionId);
        if (sessionEmitters != null) {
            sessionEmitters.remove(emitter);
            if (sessionEmitters.isEmpty()) {
                emitters.remove(sessionId);
            }
        }
    }
}
