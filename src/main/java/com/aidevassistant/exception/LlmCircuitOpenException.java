package com.aidevassistant.exception;

/**
 * Thrown when the Resilience4j circuit breaker around the LLM client is open,
 * meaning too many recent calls have failed and the system is protecting itself
 * from cascading failures.
 *
 * <p>Maps to HTTP 503 Service Unavailable. The caller should retry after a
 * short back-off period. The circuit will transition to half-open automatically
 * after the configured wait duration.
 */
public class LlmCircuitOpenException extends RuntimeException {

    public LlmCircuitOpenException(String message) {
        super(message);
    }

    public LlmCircuitOpenException(String message, Throwable cause) {
        super(message, cause);
    }
}
