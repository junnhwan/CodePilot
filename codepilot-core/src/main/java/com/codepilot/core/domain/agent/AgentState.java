package com.codepilot.core.domain.agent;

public enum AgentState {
    IDLE,
    PLANNING,
    REVIEWING,
    MERGING,
    REPORTING,
    DONE,
    FAILED;

    public boolean terminal() {
        return this == DONE || this == FAILED;
    }
}
