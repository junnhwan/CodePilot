package com.codepilot.core.application.review;

import com.codepilot.core.domain.agent.AgentDefinition;
import com.codepilot.core.domain.context.ContextPack;
import com.codepilot.core.domain.llm.LlmMessage;
import com.codepilot.core.domain.llm.ToolDefinition;
import com.codepilot.core.domain.memory.TeamConvention;
import com.codepilot.core.domain.plan.ReviewTask;

import java.util.List;
import java.util.stream.Collectors;

public final class ReviewPromptTemplates {

    private ReviewPromptTemplates() {
    }

    public static LlmMessage systemMessage(
            AgentDefinition agentDefinition,
            ReviewTask reviewTask,
            ContextPack contextPack,
            List<ToolDefinition> toolDefinitions
    ) {
        String conventions = contextPack.projectMemory().teamConventions().isEmpty()
                ? "None"
                : contextPack.projectMemory().teamConventions().stream()
                .map(TeamConvention::rule)
                .collect(Collectors.joining("\n- ", "- ", ""));

        String tools = toolDefinitions.stream()
                .map(tool -> "- %s: %s | parameters=%s".formatted(tool.name(), tool.description(), tool.parameters()))
                .collect(Collectors.joining("\n"));

        String prompt = """
                You are CodePilot's %s.

                Responsibility:
                %s

                Review task:
                - type: %s
                - target files: %s
                - focus hints: %s
                - focus areas: %s

                Team conventions:
                %s

                Available tools:
                %s

                Decision protocol:
                1. When you need more evidence, either use native tool calling or return JSON:
                   {"decision":"CALL_TOOL","tool_calls":[{"tool":"read_file","arguments":{"file_path":"..."}}]}
                2. When you are done, return JSON:
                   {"decision":"DELIVER","findings":[{"file":"...","line":1,"severity":"HIGH","confidence":0.9,"category":"security","title":"...","description":"...","suggestion":"...","evidence":["..."]}]}

                Review rules:
                - Only report issues you can justify from the diff or tool evidence.
                - Findings must stay in REPORTED state; confirmation happens later in the pipeline.
                - If there is no issue, DELIVER with an empty findings array.
                - This review loop currently supports CALL_TOOL and DELIVER only. Do not request extra context.
                - Return valid JSON when you are not using native tool calling.
                """.formatted(
                agentDefinition.agentName(),
                agentDefinition.responsibility(),
                reviewTask.type(),
                reviewTask.targetFiles(),
                reviewTask.focusHints(),
                agentDefinition.focusAreas(),
                conventions,
                tools
        );

        return new LlmMessage("system", prompt);
    }

    public static LlmMessage userMessage(ReviewTask reviewTask, ContextPack contextPack) {
        String diffSummary = contextPack.diffSummary().changedFiles().stream()
                .map(file -> "- %s | %s | +%d/-%d".formatted(
                        file.path(),
                        file.changeType(),
                        file.additions(),
                        file.deletions()
                ))
                .collect(Collectors.joining("\n"));

        String snippets = contextPack.snippets().stream()
                .map(snippet -> """
                        File: %s (%d-%d)
                        Reason: %s
                        %s
                        """.formatted(
                        snippet.filePath(),
                        snippet.startLine(),
                        snippet.endLine(),
                        snippet.reason(),
                        snippet.content()
                ))
                .collect(Collectors.joining("\n"));

        String prompt = """
                Review the diff for task %s.

                Diff summary:
                %s

                Impacted files:
                %s

                Snippets:
                %s
                """.formatted(
                reviewTask.taskId(),
                diffSummary.isBlank() ? "No changed files parsed." : diffSummary,
                contextPack.impactSet().impactedFiles(),
                snippets.isBlank() ? "No snippets available." : snippets
        );

        return new LlmMessage("user", prompt);
    }
}
