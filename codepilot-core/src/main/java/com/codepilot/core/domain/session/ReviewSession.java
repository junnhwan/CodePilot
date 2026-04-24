package com.codepilot.core.domain.session;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.context.DiffSummary;
import com.codepilot.core.domain.plan.ReviewPlan;
import com.codepilot.core.domain.review.ReviewResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ReviewSession(
        String sessionId,
        String projectId,
        Integer prNumber,
        String prUrl,
        AgentState state,
        DiffSummary diffSummary,
        ReviewPlan reviewPlan,
        ReviewResult reviewResult,
        List<SessionEvent> events,
        Instant createdAt,
        Instant completedAt
) {

    private static final Map<AgentState, Set<AgentState>> ALLOWED_TRANSITIONS = Map.of(
            AgentState.IDLE, Set.of(AgentState.PLANNING, AgentState.FAILED),
            AgentState.PLANNING, Set.of(AgentState.REVIEWING, AgentState.FAILED),
            AgentState.REVIEWING, Set.of(AgentState.MERGING, AgentState.FAILED),
            AgentState.MERGING, Set.of(AgentState.REPORTING, AgentState.FAILED),
            AgentState.REPORTING, Set.of(AgentState.DONE, AgentState.FAILED),
            AgentState.DONE, Set.of(),
            AgentState.FAILED, Set.of()
    );

    public ReviewSession {
        sessionId = requireText(sessionId, "sessionId");
        projectId = requireText(projectId, "projectId");
        prUrl = prUrl == null ? "" : prUrl;
        if (state == null) {
            throw new DomainRuleViolationException("ReviewSession[%s] state must not be null".formatted(sessionId));
        }
        events = events == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(events));
        if (createdAt == null) {
            throw new DomainRuleViolationException("ReviewSession[%s] createdAt must not be null".formatted(sessionId));
        }
        if (state.terminal() && completedAt == null) {
            throw new DomainRuleViolationException("ReviewSession[%s] terminal state %s requires completedAt"
                    .formatted(sessionId, state));
        }
    }

    public static ReviewSession initialize(
            String sessionId,
            String projectId,
            Integer prNumber,
            String prUrl,
            Instant createdAt
    ) {
        SessionEvent created = SessionEvent.of(
                sessionId,
                SessionEvent.Type.SESSION_CREATED,
                createdAt,
                Map.of(
                        "state", AgentState.IDLE.name(),
                        "projectId", projectId,
                        "prNumber", prNumber == null ? "" : prNumber,
                        "prUrl", prUrl == null ? "" : prUrl
                )
        );
        return new ReviewSession(
                sessionId,
                projectId,
                prNumber,
                prUrl,
                AgentState.IDLE,
                null,
                null,
                null,
                List.of(created),
                createdAt,
                null
        );
    }

    public ReviewSession startPlanning(DiffSummary diffSummary, Instant occurredAt) {
        requireTransition(AgentState.PLANNING);
        if (diffSummary == null) {
            throw new DomainRuleViolationException("ReviewSession[%s] cannot start planning without diffSummary"
                    .formatted(sessionId));
        }
        return transition(AgentState.PLANNING, diffSummary, reviewPlan, reviewResult, occurredAt);
    }

    public ReviewSession attachPlan(ReviewPlan reviewPlan, Instant occurredAt) {
        requireState(AgentState.PLANNING, "attach plan");
        if (reviewPlan == null) {
            throw new DomainRuleViolationException("ReviewSession[%s] cannot attach a null reviewPlan".formatted(sessionId));
        }
        if (!sessionId.equals(reviewPlan.sessionId())) {
            throw new DomainRuleViolationException("ReviewSession[%s] cannot attach plan from session %s"
                    .formatted(sessionId, reviewPlan.sessionId()));
        }
        return new ReviewSession(
                sessionId,
                projectId,
                prNumber,
                prUrl,
                state,
                diffSummary,
                reviewPlan,
                reviewResult,
                append(SessionEvent.of(
                        sessionId,
                        SessionEvent.Type.PLAN_ATTACHED,
                        occurredAt,
                        Map.of(
                                "planId", reviewPlan.planId(),
                                "taskCount", reviewPlan.taskGraph().allTasks().size(),
                                "reviewPlan", reviewPlan
                        )
                )),
                createdAt,
                completedAt
        );
    }

    public ReviewSession startReviewing(Instant occurredAt) {
        requireTransition(AgentState.REVIEWING);
        if (reviewPlan == null) {
            throw new DomainRuleViolationException("ReviewSession[%s] cannot start reviewing without a ReviewPlan"
                    .formatted(sessionId));
        }
        return transition(AgentState.REVIEWING, diffSummary, reviewPlan, reviewResult, occurredAt);
    }

    public ReviewSession startMerging(Instant occurredAt) {
        requireTransition(AgentState.MERGING);
        return transition(AgentState.MERGING, diffSummary, reviewPlan, reviewResult, occurredAt);
    }

    public ReviewSession startReporting(ReviewResult reviewResult, Instant occurredAt) {
        requireTransition(AgentState.REPORTING);
        if (reviewResult == null) {
            throw new DomainRuleViolationException("ReviewSession[%s] cannot start reporting without ReviewResult"
                    .formatted(sessionId));
        }
        return new ReviewSession(
                sessionId,
                projectId,
                prNumber,
                prUrl,
                AgentState.REPORTING,
                diffSummary,
                reviewPlan,
                reviewResult,
                append(SessionEvent.of(
                        sessionId,
                        SessionEvent.Type.SESSION_STATE_CHANGED,
                        occurredAt,
                        stateChangePayload(AgentState.REPORTING, Map.of(
                                "findingCount", reviewResult.findings().size(),
                                "partial", reviewResult.partial(),
                                "reviewResult", reviewResult
                        ))
                )),
                createdAt,
                completedAt
        );
    }

    public ReviewSession markDone(ReviewResult reviewResult, Instant occurredAt) {
        requireTransition(AgentState.DONE);
        if (reviewResult == null) {
            throw new DomainRuleViolationException("ReviewSession[%s] cannot complete without ReviewResult"
                    .formatted(sessionId));
        }
        return new ReviewSession(
                sessionId,
                projectId,
                prNumber,
                prUrl,
                AgentState.DONE,
                diffSummary,
                reviewPlan,
                reviewResult,
                append(SessionEvent.of(
                        sessionId,
                        SessionEvent.Type.REVIEW_COMPLETED,
                        occurredAt,
                        Map.of(
                                "findingCount", reviewResult.findings().size(),
                                "partial", reviewResult.partial(),
                                "reviewResult", reviewResult
                        )
                )),
                createdAt,
                occurredAt
        );
    }

    public ReviewSession fail(String reason, Instant occurredAt) {
        requireTransition(AgentState.FAILED);
        return new ReviewSession(
                sessionId,
                projectId,
                prNumber,
                prUrl,
                AgentState.FAILED,
                diffSummary,
                reviewPlan,
                reviewResult,
                append(SessionEvent.of(
                        sessionId,
                        SessionEvent.Type.REVIEW_FAILED,
                        occurredAt,
                        Map.of("reason", requireText(reason, "reason"), "state", state.name())
                )),
                createdAt,
                occurredAt
        );
    }

    private ReviewSession transition(
            AgentState nextState,
            DiffSummary nextDiffSummary,
            ReviewPlan nextReviewPlan,
            ReviewResult nextReviewResult,
            Instant occurredAt
    ) {
        return new ReviewSession(
                sessionId,
                projectId,
                prNumber,
                prUrl,
                nextState,
                nextDiffSummary,
                nextReviewPlan,
                nextReviewResult,
                append(SessionEvent.of(
                        sessionId,
                        SessionEvent.Type.SESSION_STATE_CHANGED,
                        occurredAt,
                        stateChangePayload(nextState, Map.of())
                )),
                createdAt,
                completedAt
        );
    }

    private List<SessionEvent> append(SessionEvent sessionEvent) {
        List<SessionEvent> updatedEvents = new ArrayList<>(events);
        updatedEvents.add(sessionEvent);
        return Collections.unmodifiableList(updatedEvents);
    }

    private void requireTransition(AgentState nextState) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(state, Set.of()).contains(nextState)) {
            throw new DomainRuleViolationException("ReviewSession[%s] cannot transition from %s to %s"
                    .formatted(sessionId, state, nextState));
        }
    }

    private Map<String, Object> stateChangePayload(AgentState nextState, Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", state.name());
        payload.put("to", nextState.name());
        payload.putAll(extraPayload);
        return Collections.unmodifiableMap(payload);
    }

    private void requireState(AgentState expected, String action) {
        if (state != expected) {
            throw new DomainRuleViolationException("ReviewSession[%s] cannot %s from %s"
                    .formatted(sessionId, action, state));
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
