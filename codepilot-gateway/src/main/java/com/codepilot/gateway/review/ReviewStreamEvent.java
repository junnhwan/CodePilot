package com.codepilot.gateway.review;

public record ReviewStreamEvent(
        String event,
        Object payload
) {
}
