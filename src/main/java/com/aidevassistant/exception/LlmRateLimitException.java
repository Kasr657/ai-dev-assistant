package com.aidevassistant.exception;

/**
 * Thrown when the LLM provider returns HTTP 429 Too Many Requests.
 *
 * <p>Indicates that the API rate limit or quota has been exceeded. The caller
 * should back off before retrying. Maps to HTTP 429 in the error response.
 */
public class LlmRateLimitException extends RuntimeException {

    public LlmRateLimitException(String message) {
        super(message);
    }

    public LlmRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
