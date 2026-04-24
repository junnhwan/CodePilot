package com.codepilot.core.domain.memory;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.plan.ReviewTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record GlobalKnowledgeEntry(
        String entryId,
        ReviewTask.TaskType taskType,
        String title,
        String guidance,
        List<String> triggerTokens
) {

    public GlobalKnowledgeEntry {
        entryId = requireText(entryId, "entryId");
        if (taskType == null) {
            throw new DomainRuleViolationException("GlobalKnowledgeEntry[%s] taskType must not be null".formatted(entryId));
        }
        title = requireText(title, "title");
        guidance = requireText(guidance, "guidance");
        triggerTokens = triggerTokens == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(triggerTokens.stream()
                .filter(token -> token != null && !token.isBlank())
                .map(String::trim)
                .toList()));
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
