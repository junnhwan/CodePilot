package com.codepilot.core.domain.agent;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record AgentDefinition(
        String agentName,
        String responsibility,
        Set<AgentState> activeStates,
        Set<AgentDecision.Type> supportedDecisions,
        List<String> focusAreas
) {

    public AgentDefinition {
        agentName = requireText(agentName, "agentName");
        responsibility = requireText(responsibility, "responsibility");
        activeStates = activeStates == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(activeStates));
        supportedDecisions = supportedDecisions == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(supportedDecisions));
        focusAreas = focusAreas == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(focusAreas));

        if (supportedDecisions.isEmpty()) {
            throw new DomainRuleViolationException("AgentDefinition[%s] must expose at least one supported decision"
                    .formatted(agentName));
        }
    }

    public boolean runsIn(AgentState state) {
        return activeStates.contains(state);
    }

    public boolean supports(AgentDecision.Type type) {
        return supportedDecisions.contains(type);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainRuleViolationException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
