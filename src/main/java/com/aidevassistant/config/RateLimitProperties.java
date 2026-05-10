package com.aidevassistant.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized configuration for the global API rate limiter.
 *
 * <p>All properties are bound from the {@code rate-limit.*} namespace and can be
 * overridden via environment variables using Spring Boot's relaxed binding rules.
 *
 * <p>The rate limiter uses a token bucket algorithm (Bucket4j). Tokens refill at
 * {@code requestsPerSecond} tokens per second. The bucket can hold up to
 * {@code burstCapacity} tokens, allowing short bursts above the steady-state rate.
 *
 * <p>Example: with {@code requestsPerSecond=10} and {@code burstCapacity=20}, a caller
 * can send 20 requests instantly (consuming the burst), then sustain 10 req/s thereafter.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /**
     * Whether rate limiting is enabled.
     * Override via environment variable: {@code RATE_LIMIT_ENABLED}
     * Default: {@code true}
     */
    private boolean enabled = true;

    /**
     * Steady-state refill rate — tokens added to the bucket per second.
     * Override via environment variable: {@code RATE_LIMIT_REQUESTS_PER_SECOND}
     * Default: {@code 10}
     */
    @Min(value = 1, message = "rate-limit.requests-per-second must be at least 1")
    private int requestsPerSecond = 10;

    /**
     * Maximum bucket capacity — the maximum number of tokens that can accumulate,
     * allowing short bursts above the steady-state rate.
     * Override via environment variable: {@code RATE_LIMIT_BURST_CAPACITY}
     * Default: {@code 20}
     */
    @Min(value = 1, message = "rate-limit.burst-capacity must be at least 1")
    private int burstCapacity = 20;
}
