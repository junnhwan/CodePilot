package com.codepilot.core.application.tool;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.llm.ToolDefinition;
import com.codepilot.core.domain.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {

    private final Map<String, Tool> toolsByName;

    public ToolRegistry(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            throw new DomainRuleViolationException("ToolRegistry must register at least one tool");
        }

        Map<String, Tool> indexed = new LinkedHashMap<>();
        for (Tool tool : tools) {
            if (tool == null) {
                throw new DomainRuleViolationException("ToolRegistry cannot register a null tool");
            }
            if (indexed.putIfAbsent(tool.name(), tool) != null) {
                throw new DomainRuleViolationException("ToolRegistry contains duplicate tool " + tool.name());
            }
        }
        this.toolsByName = Map.copyOf(indexed);
    }

    public Tool getRequired(String toolName) {
        Tool tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new DomainRuleViolationException("Unknown tool " + toolName);
        }
        return tool;
    }

    public List<ToolDefinition> toolDefinitions() {
        return toolsByName.values().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parameterSchema()))
                .toList();
    }
}
