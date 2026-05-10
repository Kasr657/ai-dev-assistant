package com.aidevassistant.exception;

/**
 * Thrown when an unexpected HTTP or network error occurs while communicating with the LLM provider.
 * Maps to HTTP 500 Internal Server Error.
 */
public class LlmCommunicationException extends RuntimeException {

    public LlmCommunicationException(String message) {
        super(message);
    }

    public LlmCommunicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
