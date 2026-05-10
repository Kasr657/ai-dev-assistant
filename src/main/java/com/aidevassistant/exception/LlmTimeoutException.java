package com.aidevassistant.exception;

/**
 * Thrown when the LLM provider does not respond within the configured timeout.
 * Maps to HTTP 504 Gateway Timeout.
 */
public class LlmTimeoutException extends RuntimeException {

    public LlmTimeoutException(String message) {
        super(message);
    }

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
