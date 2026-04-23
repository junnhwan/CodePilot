package com.codepilot.core.domain.llm;

import reactor.core.publisher.Flux;

public interface LlmClient {

    LlmResponse chat(LlmRequest request);

    Flux<LlmChunk> stream(LlmRequest request);
}
