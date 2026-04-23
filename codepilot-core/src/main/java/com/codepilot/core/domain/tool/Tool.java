package com.codepilot.core.domain.tool;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    Map<String, Object> parameterSchema();

    boolean readOnly();

    boolean exclusive();

    ToolResult execute(ToolCall call);
}
