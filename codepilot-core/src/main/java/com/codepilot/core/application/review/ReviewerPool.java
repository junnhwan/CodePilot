package com.codepilot.core.application.review;

import com.codepilot.core.domain.agent.AgentDecision;
import com.codepilot.core.domain.agent.AgentDefinition;
import com.codepilot.core.domain.agent.AgentState;
import com.codepilot.core.domain.plan.ReviewTask;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReviewerPool {

    private final Map<ReviewTask.TaskType, AgentDefinition> reviewersByType;

    public ReviewerPool() {
        this.reviewersByType = Map.of(
                ReviewTask.TaskType.SECURITY, reviewer(
                        "security-reviewer",
                        "Review changed code for high-signal security defects.",
                        List.of("input validation", "authorization", "secret handling")
                ),
                ReviewTask.TaskType.PERF, reviewer(
                        "perf-reviewer",
                        "Review changed code for performance regressions and inefficient data access.",
                        List.of("query patterns", "loop hotspots", "allocation churn")
                ),
                ReviewTask.TaskType.STYLE, reviewer(
                        "style-reviewer",
                        "Review changed code for consistency with project naming, formatting, and local conventions.",
                        List.of("naming", "project terminology", "signal-to-noise comments")
                ),
                ReviewTask.TaskType.MAINTAIN, reviewer(
                        "maintainability-reviewer",
                        "Review changed code for complexity, duplication, and weak module boundaries.",
                        List.of("complexity", "duplication", "glue code")
                )
        );
    }

    public AgentDefinition reviewerFor(ReviewTask reviewTask) {
        AgentDefinition reviewer = reviewersByType.get(reviewTask.type());
        if (reviewer == null) {
            throw new IllegalStateException("Missing reviewer for task type " + reviewTask.type());
        }
        return reviewer;
    }

    private AgentDefinition reviewer(String agentName, String responsibility, List<String> focusAreas) {
        return new AgentDefinition(
                agentName,
                responsibility,
                Set.of(AgentState.REVIEWING),
                Set.of(AgentDecision.Type.CALL_TOOL, AgentDecision.Type.DELIVER),
                focusAreas
        );
    }
}
