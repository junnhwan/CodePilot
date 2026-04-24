package com.codepilot.core.application.session;

import com.codepilot.core.application.review.MergeAgent;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.plan.ReviewTask;
import com.codepilot.core.domain.plan.TaskGraph;
import com.codepilot.core.domain.review.Finding;
import com.codepilot.core.domain.review.ReviewResult;
import com.codepilot.core.domain.review.Severity;
import com.codepilot.core.domain.session.ReviewSession;
import com.codepilot.core.domain.session.ReviewSessionRepository;
import com.codepilot.core.domain.session.SessionEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SessionStore {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build()
            .copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ReviewSessionRepository reviewSessionRepository;

    private final MergeAgent mergeAgent;

    public SessionStore(ReviewSessionRepository reviewSessionRepository) {
        this(reviewSessionRepository, new MergeAgent());
    }

    SessionStore(ReviewSessionRepository reviewSessionRepository, MergeAgent mergeAgent) {
        this.reviewSessionRepository = reviewSessionRepository;
        this.mergeAgent = mergeAgent;
    }

    public Optional<RestoredSession> restore(String sessionId) {
        List<SessionEvent> events = reviewSessionRepository.findEvents(sessionId);
        Optional<ReviewSession> checkpoint = reviewSessionRepository.findById(sessionId);
        if (checkpoint.isEmpty() && events.isEmpty()) {
            return Optional.empty();
        }

        List<SessionEvent> orderedEvents = events.stream()
                .sorted(Comparator.comparing(SessionEvent::occurredAt))
                .toList();
        ReviewSession replayedSession = replaySession(sessionId, orderedEvents);
        ReviewSession baseSession = checkpoint.orElse(replayedSession);
        List<ReviewResult> completedTaskResults = replayCompletedTaskResults(sessionId, orderedEvents);

        ReviewPlan replayedPlan = baseSession.reviewPlan() != null ? baseSession.reviewPlan() : replayedSession.reviewPlan();
        ReviewPlan effectivePlan = replayTaskGraph(replayedPlan, orderedEvents, completedTaskResults);
        ReviewResult effectiveReviewResult = resolveReviewResult(baseSession, replayedSession, completedTaskResults);
        ReviewSession restoredSession = withRecoveredState(baseSession, effectivePlan, effectiveReviewResult, orderedEvents);
        return Optional.of(new RestoredSession(restoredSession, completedTaskResults));
    }

    private ReviewSession replaySession(String sessionId, List<SessionEvent> events) {
        SessionEvent created = events.stream()
                .filter(event -> event.type() == SessionEvent.Type.SESSION_CREATED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing SESSION_CREATED event for session " + sessionId));
        AgentState state = AgentState.IDLE;
        Instant completedAt = null;

        for (SessionEvent event : events) {
            switch (event.type()) {
                case SESSION_STATE_CHANGED -> state = AgentState.valueOf(text(event.payload(), "to", state.name()));
                case REVIEW_COMPLETED -> {
                    state = AgentState.DONE;
                    completedAt = event.occurredAt();
                }
                case REVIEW_FAILED -> {
                    state = AgentState.FAILED;
                    completedAt = event.occurredAt();
                }
                default -> {
                }
            }
        }

        ReviewPlan replayedPlan = replayPlan(sessionId, events);
        ReviewResult replayedReviewResult = replayReviewResult(sessionId, events);
        return new ReviewSession(
                sessionId,
                text(created.payload(), "projectId", "unknown-project"),
                intValue(created.payload().get("prNumber")),
                text(created.payload(), "prUrl", ""),
                state,
                replayedPlan == null ? null : replayedPlan.diffSummary(),
                replayedPlan,
                replayedReviewResult,
                events,
                created.occurredAt(),
                completedAt
        );
    }

    private ReviewPlan replayPlan(String sessionId, List<SessionEvent> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            SessionEvent event = events.get(index);
            if (event.type() == SessionEvent.Type.PLAN_ATTACHED && event.payload().containsKey("reviewPlan")) {
                return convert(sessionId, event.payload().get("reviewPlan"), ReviewPlan.class, "reviewPlan");
            }
        }
        return null;
    }

    private ReviewResult replayReviewResult(String sessionId, List<SessionEvent> events) {
        for (int index = events.size() - 1; index >= 0; index--) {
            SessionEvent event = events.get(index);
            if (event.payload().containsKey("reviewResult")) {
                return convert(sessionId, event.payload().get("reviewResult"), ReviewResult.class, "reviewResult");
            }
        }
        return null;
    }

    private ReviewResult resolveReviewResult(
            ReviewSession baseSession,
            ReviewSession replayedSession,
            List<ReviewResult> completedTaskResults
    ) {
        if (baseSession.reviewResult() != null) {
            return baseSession.reviewResult();
        }
        if (replayedSession.reviewResult() != null) {
            return replayedSession.reviewResult();
        }
        if ((baseSession.state() == AgentState.REPORTING || baseSession.state() == AgentState.DONE)
                && !completedTaskResults.isEmpty()) {
            // Legacy events may miss an explicit reviewResult payload. Re-merge once during restore
            // so report/dream can continue without re-running reviewer tasks.
            return mergeAgent.merge(baseSession.sessionId(), completedTaskResults);
        }
        return null;
    }

    private ReviewPlan replayTaskGraph(
            ReviewPlan reviewPlan,
            List<SessionEvent> events,
            List<ReviewResult> completedTaskResults
    ) {
        if (reviewPlan == null) {
            return null;
        }

        Map<String, ReviewResult> completedByTask = new LinkedHashMap<>();
        for (ReviewResult taskResult : completedTaskResults) {
            String taskId = taskResult.findings().isEmpty()
                    ? taskIdFromCompletedEvent(events, taskResult.generatedAt())
                    : taskResult.findings().getFirst().taskId();
            if (taskId != null) {
                completedByTask.put(taskId, taskResult);
            }
        }

        TaskGraph taskGraph = reviewPlan.taskGraph();
        for (ReviewTask task : reviewPlan.taskGraph().allTasks()) {
            if (completedByTask.containsKey(task.taskId())) {
                taskGraph = taskGraph.replace(task.markReady().start().complete());
            }
        }

        return new ReviewPlan(
                reviewPlan.planId(),
                reviewPlan.sessionId(),
                reviewPlan.diffSummary(),
                taskGraph,
                reviewPlan.strategy()
        );
    }

    private String taskIdFromCompletedEvent(List<SessionEvent> events, Instant occurredAt) {
        return events.stream()
                .filter(event -> event.type() == SessionEvent.Type.TASK_COMPLETED)
                .filter(event -> event.occurredAt().equals(occurredAt))
                .map(event -> text(event.payload(), "taskId", null))
                .findFirst()
                .orElse(null);
    }

    private List<ReviewResult> replayCompletedTaskResults(String sessionId, List<SessionEvent> events) {
        Map<String, TaskAttempt> activeAttempts = new LinkedHashMap<>();
        Map<String, ReviewResult> completedResults = new LinkedHashMap<>();

        for (SessionEvent event : events) {
            switch (event.type()) {
                case TASK_STARTED -> activeAttempts.put(
                        text(event.payload(), "taskId", ""),
                        new TaskAttempt(text(event.payload(), "taskId", ""), new ArrayList<>())
                );
                case FINDING_REPORTED -> {
                    String taskId = text(event.payload(), "taskId", "");
                    TaskAttempt attempt = activeAttempts.computeIfAbsent(taskId, TaskAttempt::new);
                    attempt.findings().add(toFinding(event.payload()));
                }
                case TASK_COMPLETED -> {
                    String taskId = text(event.payload(), "taskId", "");
                    TaskAttempt attempt = activeAttempts.remove(taskId);
                    List<Finding> findings = attempt == null ? List.of() : List.copyOf(attempt.findings());
                    boolean partial = booleanValue(event.payload().get("partial"));
                    completedResults.put(taskId, new ReviewResult(sessionId, findings, partial, event.occurredAt()));
                }
                default -> {
                }
            }
        }

        return List.copyOf(completedResults.values());
    }

    private Finding toFinding(Map<String, Object> payload) {
        Integer startLine = intValue(payload.get("startLine"));
        Integer endLine = intValue(payload.get("endLine"));
        return Finding.reported(
                text(payload, "findingId", text(payload, "taskId", "") + "-finding-restored"),
                text(payload, "taskId", ""),
                text(payload, "category", "security"),
                Severity.valueOf(text(payload, "severity", Severity.MEDIUM.name())),
                doubleValue(payload.get("confidence")),
                new Finding.CodeLocation(
                        text(payload, "file", ""),
                        startLine == null ? 1 : startLine,
                        endLine == null ? (startLine == null ? 1 : startLine) : endLine
                ),
                text(payload, "title", "Recovered finding"),
                text(payload, "description", "Recovered from session event."),
                text(payload, "suggestion", ""),
                stringList(payload.get("evidence"))
        );
    }

    private ReviewSession withRecoveredState(
            ReviewSession session,
            ReviewPlan reviewPlan,
            ReviewResult reviewResult,
            List<SessionEvent> events
    ) {
        return new ReviewSession(
                session.sessionId(),
                session.projectId(),
                session.prNumber(),
                session.prUrl(),
                session.state(),
                session.diffSummary() != null ? session.diffSummary() : reviewPlan == null ? null : reviewPlan.diffSummary(),
                reviewPlan,
                reviewResult,
                events,
                session.createdAt(),
                session.completedAt()
        );
    }

    private <T> T convert(String sessionId, Object value, Class<T> type, String fieldName) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        try {
            return OBJECT_MAPPER.convertValue(value, type);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Failed to restore %s for session %s".formatted(fieldName, sessionId),
                    exception
            );
        }
    }

    private String text(Map<String, Object> payload, String key, String defaultValue) {
        Object value = payload.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text && text.isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private double doubleValue(Object value) {
        if (value == null) {
            return 0.5d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry != null) {
                    result.add(entry.toString());
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    public record RestoredSession(
            ReviewSession session,
            List<ReviewResult> completedTaskResults
    ) {
    }

    private record TaskAttempt(
            String taskId,
            List<Finding> findings
    ) {
        private TaskAttempt(String taskId) {
            this(taskId, new ArrayList<>());
        }
    }
}
