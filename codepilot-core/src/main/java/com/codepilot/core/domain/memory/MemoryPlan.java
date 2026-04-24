package com.codepilot.core.domain.memory;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record MemoryPlan(
        String projectId,
        List<ReviewPattern> patternsToAdd,
        List<TeamConvention> conventionsToAdd
) {

    public MemoryPlan {
        projectId = requireText(projectId, "projectId");
        patternsToAdd = patternsToAdd == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(patternsToAdd));
        conventionsToAdd = conventionsToAdd == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(conventionsToAdd));
    }

    public boolean isEmpty() {
        return patternsToAdd.isEmpty() && conventionsToAdd.isEmpty();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
