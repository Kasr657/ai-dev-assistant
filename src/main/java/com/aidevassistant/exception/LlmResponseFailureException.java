package com.aidevassistant.exception;

/**
 * Thrown when the LLM response remains invalid after all retry attempts are exhausted.
 * Maps to HTTP 502 Bad Gateway.
 */
public class LlmResponseFailureException extends RuntimeException {

    public LlmResponseFailureException(String message) {
        super(message);
    }

    public LlmResponseFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
