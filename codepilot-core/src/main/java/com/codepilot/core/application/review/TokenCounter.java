package com.codepilot.core.application.review;

import com.codepilot.core.domain.llm.LlmMessage;

import java.util.List;

public final class TokenCounter {

    public int countText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0d));
    }

    public int countMessages(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (LlmMessage message : messages) {
            total += 4;
            total += countText(message.role());
            total += countText(message.content());
        }
        return total + 2;
    }
}
