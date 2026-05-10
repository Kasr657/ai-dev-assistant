package com.aidevassistant.exception;

/**
 * Thrown when the LLM provider refuses to fulfill the request due to content policy,
 * safety filters, or other provider-level restrictions.
 *
 * <p>OpenAI structured output responses may include a {@code refusal} field in
 * {@code choices[0].message} instead of a {@code content} field when the model
 * declines to respond. This exception surfaces that refusal to the caller.
 *
 * <p>Maps to HTTP 422 Unprocessable Content (RFC 9110) — the request was well-formed
 * but the provider declined to process it. The caller may retry with different input.
 */
public class LlmRefusalException extends RuntimeException {

    private final String refusalReason;

    public LlmRefusalException(String refusalReason) {
        super("LLM refused to process the request: " + refusalReason);
        this.refusalReason = refusalReason;
    }

    /**
     * Returns the raw refusal reason string from the provider response.
     */
    public String getRefusalReason() {
        return refusalReason;
    }
}
