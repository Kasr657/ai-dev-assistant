package com.aidevassistant.exception;

/**
 * Thrown when the LLM provider returns HTTP 401 Unauthorized.
 *
 * <p>Indicates that the configured API key is missing, invalid, or revoked.
 * This is a configuration error that requires operator intervention.
 * Maps to HTTP 500 in the error response (it is an internal misconfiguration,
 * not a client error).
 */
public class LlmAuthException extends RuntimeException {

    public LlmAuthException(String message) {
        super(message);
    }

    public LlmAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
