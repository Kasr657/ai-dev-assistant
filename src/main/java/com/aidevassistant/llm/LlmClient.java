package com.aidevassistant.llm;

import reactor.core.publisher.Mono;

/**
 * Reactive abstraction for communicating with an external LLM provider.
 *
 * <p>Implementations return a {@link Mono} that emits the raw response string when
 * the provider responds. Using a reactive return type allows the caller to compose
 * timeout, retry, and cancellation operators directly on the reactive chain — ensuring
 * that a service-level deadline actually cancels the in-flight HTTP request rather
 * than just abandoning a blocked thread.
 *
 * <p>Designed for extensibility: swap providers (e.g. OpenAI → Claude) by providing
 * a new implementation without changing any other component.
 *
 * <p>Current implementation: {@link com.aidevassistant.llm.OpenAiClient}.
 */
public interface LlmClient {

    /**
     * Sends the given prompt to the LLM provider and returns a {@link Mono} that
     * emits the raw response string on success.
     *
     * <p>The returned {@link Mono} is cold — the HTTP call is not made until the
     * {@code Mono} is subscribed to (e.g. via {@code .block()} or {@code .subscribe()}).
     *
     * @param prompt the fully constructed prompt to send; must not be blank
     * @return a {@link Mono} emitting the raw response string; never emits {@code null}
     */
    Mono<String> call(String prompt);
}
