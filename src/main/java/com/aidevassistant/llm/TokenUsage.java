package com.aidevassistant.llm;

/**
 * Immutable value object holding token usage metadata from an OpenAI API response.
 *
 * <p>Extracted from the {@code usage} field of the Chat Completions response and
 * logged at INFO level after every successful call for cost and performance observability.
 *
 * @param model            the model name reported in the response (e.g. {@code gpt-4o})
 * @param promptTokens     number of tokens consumed by the input prompt
 * @param completionTokens number of tokens generated in the response
 * @param totalTokens      total tokens consumed ({@code promptTokens + completionTokens})
 */
public record TokenUsage(String model, int promptTokens, int completionTokens, int totalTokens) {

    /** Sentinel value returned when the {@code usage} field is absent in the response. */
    public static final TokenUsage UNAVAILABLE = new TokenUsage("unknown", 0, 0, 0);

    /** Returns {@code true} if this instance represents a real usage reading. */
    public boolean isAvailable() {
        return !model.equals("unknown") || totalTokens > 0;
    }
}
