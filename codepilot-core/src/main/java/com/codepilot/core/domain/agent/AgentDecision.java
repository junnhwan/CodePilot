package com.codepilot.core.domain.agent;

import com.codepilot.core.domain.DomainRuleViolationException;
import com.codepilot.core.domain.tool.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record AgentDecision(
        Type type,
        String rationale,
        List<ToolCall> toolCalls,
        List<String> requestedFiles
) {

    public AgentDecision {
        if (type == null) {
            throw new DomainRuleViolationException("AgentDecision type must not be null");
        }
        rationale = rationale == null ? "" : rationale.trim();
        toolCalls = toolCalls == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        requestedFiles = requestedFiles == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(requestedFiles));

        if (type == Type.CALL_TOOL && toolCalls.isEmpty()) {
            throw new DomainRuleViolationException("CALL_TOOL decision requires at least one ToolCall");
        }
        if (type == Type.ASK_CONTEXT && requestedFiles.isEmpty()) {
            throw new DomainRuleViolationException("ASK_CONTEXT decision requires at least one requested file");
        }
    }

    public static AgentDecision dispatch(String rationale) {
        return new AgentDecision(Type.DISPATCH, rationale, List.of(), List.of());
    }

    public static AgentDecision waitForSignal(String rationale) {
        return new AgentDecision(Type.WAIT, rationale, List.of(), List.of());
    }

    public static AgentDecision escalate(String rationale) {
        return new AgentDecision(Type.ESCALATE, rationale, List.of(), List.of());
    }

    public static AgentDecision complete(String rationale) {
        return new AgentDecision(Type.COMPLETE, rationale, List.of(), List.of());
    }

    public static AgentDecision callTool(String rationale, List<ToolCall> toolCalls) {
        return new AgentDecision(Type.CALL_TOOL, rationale, toolCalls, List.of());
    }

    public static AgentDecision askContext(String rationale, List<String> requestedFiles) {
        return new AgentDecision(Type.ASK_CONTEXT, rationale, List.of(), requestedFiles);
    }

    public static AgentDecision deliver(String rationale) {
        return new AgentDecision(Type.DELIVER, rationale, List.of(), List.of());
    }

    public enum Type {
        DISPATCH,
        WAIT,
        ESCALATE,
        COMPLETE,
        CALL_TOOL,
        ASK_CONTEXT,
        DELIVER
    }
}
