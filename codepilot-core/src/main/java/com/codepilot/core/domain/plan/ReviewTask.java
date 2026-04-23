package com.codepilot.core.domain.plan;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public record ReviewTask(
        String taskId,
        TaskType type,
        State state,
        Priority priority,
        List<String> targetFiles,
        List<String> focusHints,
        List<String> dependencies
) {

    public ReviewTask {
        taskId = requireText(taskId, "taskId");
        if (type == null) {
            throw new DomainRuleViolationException("ReviewTask[%s] type must not be null".formatted(taskId));
        }
        if (state == null) {
            throw new DomainRuleViolationException("ReviewTask[%s] state must not be null".formatted(taskId));
        }
        if (priority == null) {
            throw new DomainRuleViolationException("ReviewTask[%s] priority must not be null".formatted(taskId));
        }
        targetFiles = targetFiles == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(targetFiles));
        focusHints = focusHints == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(focusHints));
        dependencies = dependencies == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(dependencies)));

        if (targetFiles.isEmpty()) {
            throw new DomainRuleViolationException("ReviewTask[%s] must target at least one file".formatted(taskId));
        }
        if (dependencies.contains(taskId)) {
            throw new DomainRuleViolationException("ReviewTask[%s] cannot depend on itself".formatted(taskId));
        }
    }

    public static ReviewTask pending(
            String taskId,
            TaskType type,
            Priority priority,
            List<String> targetFiles,
            List<String> focusHints,
            List<String> dependencies
    ) {
        return new ReviewTask(taskId, type, State.PENDING, priority, targetFiles, focusHints, dependencies);
    }

    public ReviewTask markReady() {
        requireState(State.PENDING, "mark ready");
        return withState(State.READY);
    }

    public ReviewTask start() {
        requireState(State.READY, "start");
        return withState(State.IN_PROGRESS);
    }

    public ReviewTask complete() {
        requireState(State.IN_PROGRESS, "complete");
        return withState(State.COMPLETED);
    }

    public ReviewTask skip() {
        if (isTerminal()) {
            throw new DomainRuleViolationException("ReviewTask[%s] is already terminal in state %s"
                    .formatted(taskId, state));
        }
        return withState(State.SKIPPED);
    }

    public ReviewTask timeout() {
        requireState(State.IN_PROGRESS, "timeout");
        return withState(State.TIMEOUT);
    }

    public boolean isTerminal() {
        return state == State.COMPLETED || state == State.SKIPPED || state == State.TIMEOUT;
    }

    private ReviewTask withState(State nextState) {
        return new ReviewTask(taskId, type, nextState, priority, targetFiles, focusHints, dependencies);
    }

    private void requireState(State expected, String action) {
        if (state != expected) {
            throw new DomainRuleViolationException("ReviewTask[%s] cannot %s from %s"
                    .formatted(taskId, action, state));
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public enum TaskType {
        SECURITY,
        PERF,
        STYLE,
        MAINTAIN
    }

    public enum State {
        PENDING,
        READY,
        IN_PROGRESS,
        COMPLETED,
        SKIPPED,
        TIMEOUT
    }

    public enum Priority {
        HIGH,
        MEDIUM,
        LOW
    }
}
