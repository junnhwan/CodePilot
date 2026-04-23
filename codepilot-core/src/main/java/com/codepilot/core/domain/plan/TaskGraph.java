package com.codepilot.core.domain.plan;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record TaskGraph(Map<String, ReviewTask> tasks) {

    public TaskGraph {
        tasks = tasks == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
        validateDependencies(tasks);
        detectCycle(tasks);
    }

    public static TaskGraph of(List<ReviewTask> tasks) {
        Map<String, ReviewTask> orderedTasks = new LinkedHashMap<>();
        if (tasks != null) {
            for (ReviewTask task : tasks) {
                ReviewTask previous = orderedTasks.putIfAbsent(task.taskId(), task);
                if (previous != null) {
                    throw new DomainRuleViolationException("TaskGraph contains duplicated task id %s"
                            .formatted(task.taskId()));
                }
            }
        }
        return new TaskGraph(orderedTasks);
    }

    public List<ReviewTask> allTasks() {
        return List.copyOf(tasks.values());
    }

    public List<ReviewTask> availableTasks() {
        return tasks.values().stream()
                .filter(task -> task.state() == ReviewTask.State.PENDING || task.state() == ReviewTask.State.READY)
                .filter(this::dependenciesSatisfied)
                .map(task -> task.state() == ReviewTask.State.PENDING ? task.markReady() : task)
                .toList();
    }

    public TaskGraph replace(ReviewTask updatedTask) {
        if (!tasks.containsKey(updatedTask.taskId())) {
            throw new DomainRuleViolationException("TaskGraph does not contain task %s".formatted(updatedTask.taskId()));
        }
        Map<String, ReviewTask> updatedTasks = new LinkedHashMap<>(tasks);
        updatedTasks.put(updatedTask.taskId(), updatedTask);
        return new TaskGraph(updatedTasks);
    }

    private boolean dependenciesSatisfied(ReviewTask task) {
        return task.dependencies().stream()
                .map(tasks::get)
                .allMatch(ReviewTask::isTerminal);
    }

    private static void validateDependencies(Map<String, ReviewTask> tasks) {
        for (ReviewTask task : tasks.values()) {
            for (String dependency : task.dependencies()) {
                if (!tasks.containsKey(dependency)) {
                    throw new DomainRuleViolationException("ReviewTask[%s] depends on missing task %s"
                            .formatted(task.taskId(), dependency));
                }
            }
        }
    }

    private static void detectCycle(Map<String, ReviewTask> tasks) {
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();

        for (String taskId : tasks.keySet()) {
            walk(taskId, tasks, visiting, visited, new ArrayDeque<>());
        }
    }

    private static void walk(
            String taskId,
            Map<String, ReviewTask> tasks,
            Set<String> visiting,
            Set<String> visited,
            ArrayDeque<String> path
    ) {
        if (visited.contains(taskId)) {
            return;
        }
        if (!visiting.add(taskId)) {
            path.addLast(taskId);
            throw new DomainRuleViolationException("TaskGraph contains dependency cycle: " + String.join(" -> ", path));
        }

        path.addLast(taskId);
        ReviewTask task = tasks.get(taskId);
        for (String dependency : task.dependencies()) {
            walk(dependency, tasks, visiting, visited, path);
        }
        path.removeLast();
        visiting.remove(taskId);
        visited.add(taskId);
    }
}
