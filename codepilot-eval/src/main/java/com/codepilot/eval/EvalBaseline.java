package com.codepilot.eval;

import java.util.Locale;

public enum EvalBaseline {
    CODEPILOT,
    DIRECT_LLM,
    FULL_CONTEXT_LLM,
    LINT_ONLY;

    public String cliName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EvalBaseline parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Eval baseline must not be blank");
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return EvalBaseline.valueOf(normalized);
    }
}
