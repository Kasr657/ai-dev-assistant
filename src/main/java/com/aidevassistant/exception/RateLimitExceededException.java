package com.aidevassistant.exception;

/**
 * Thrown when the global API rate limit is exceeded.
 *
 * <p>The rate limiter uses a token bucket algorithm. When the bucket is empty,
 * incoming requests are rejected immediately rather than queued.
 *
 * <p>Maps to HTTP 429 Too Many Requests. The response includes a
 * {@code Retry-After: 1} header indicating the caller should wait at least
 * 1 second before retrying.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
