package com.aidevassistant.config;

import com.aidevassistant.exception.LlmAuthException;
import com.aidevassistant.exception.LlmRateLimitException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures the Resilience4j circuit breaker that wraps all LLM API calls.
 *
 * <h3>Circuit breaker behaviour</h3>
 * <ul>
 *   <li><strong>Closed (normal):</strong> calls pass through. Failure rate is tracked
 *       over a sliding window of 10 calls.</li>
 *   <li><strong>Open (tripped):</strong> calls fail immediately with
 *       {@link com.aidevassistant.exception.LlmCircuitOpenException} (HTTP 503).
 *       The circuit stays open for 30 seconds before transitioning to half-open.</li>
 *   <li><strong>Half-open (probing):</strong> 3 test calls are allowed. If they succeed,
 *       the circuit closes. If they fail, it opens again.</li>
 * </ul>
 *
 * <h3>What counts as a failure</h3>
 * <p>All exceptions count as failures <em>except</em>:
 * <ul>
 *   <li>{@link LlmAuthException} — this is a configuration error, not a transient failure</li>
 *   <li>{@link LlmRateLimitException} — rate limits should be handled by back-off, not circuit breaking</li>
 * </ul>
 *
 * <h3>Metrics</h3>
 * <p>Circuit breaker state and call statistics are automatically published to Micrometer
 * under the {@code resilience4j.circuitbreaker.*} metric namespace, visible at
 * {@code /actuator/prometheus}.
 */
@Configuration
public class LlmCircuitBreakerConfig {

    /** Name used to identify this circuit breaker in metrics and logs. */
    public static final String CIRCUIT_BREAKER_NAME = "llm-openai";

    /**
     * Creates and registers the LLM circuit breaker.
     *
     * <p>Configuration:
     * <ul>
     *   <li>Sliding window: 10 calls (count-based)</li>
     *   <li>Failure rate threshold: 50% — opens after 5 failures in 10 calls</li>
     *   <li>Wait duration in open state: 30 seconds</li>
     *   <li>Half-open permitted calls: 3</li>
     *   <li>Slow call threshold: 25 seconds (calls taking longer count as slow)</li>
     *   <li>Slow call rate threshold: 80% — opens if 80% of calls are slow</li>
     * </ul>
     *
     * @param meterRegistry Micrometer registry for publishing circuit breaker metrics
     * @return a configured {@link CircuitBreaker} instance
     */
    @Bean
    public CircuitBreaker llmCircuitBreaker(MeterRegistry meterRegistry) {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        // Count-based sliding window of 10 calls
                        .slidingWindowType(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        // Open circuit when ≥50% of calls fail
                        .failureRateThreshold(50.0f)
                        // Stay open for 30 seconds before probing
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        // Allow 3 test calls in half-open state
                        .permittedNumberOfCallsInHalfOpenState(3)
                        // Calls taking >25s count as slow (matches service timeout)
                        .slowCallDurationThreshold(Duration.ofSeconds(25))
                        // Open circuit when ≥80% of calls are slow
                        .slowCallRateThreshold(80.0f)
                        // Auth errors and rate limits are not transient — don't count as failures
                        .ignoreExceptions(LlmAuthException.class, LlmRateLimitException.class)
                        .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        // Publish circuit breaker metrics to Micrometer (visible at /actuator/prometheus)
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)
                .bindTo(meterRegistry);

        return circuitBreaker;
    }
}
