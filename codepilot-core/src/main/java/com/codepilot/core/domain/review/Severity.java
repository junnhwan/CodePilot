package com.codepilot.core.domain.review;

public enum Severity {
    CRITICAL(5),
    HIGH(4),
    MEDIUM(3),
    LOW(2),
    INFO(1);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public boolean moreSevereThan(Severity other) {
        return this.weight > other.weight;
    }
}
