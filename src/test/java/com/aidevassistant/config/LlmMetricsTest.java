package com.aidevassistant.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LlmMetrics}.
 *
 * <p>Uses {@link SimpleMeterRegistry} (in-memory, no external dependencies) to verify
 * that each metric method correctly registers and increments the expected meter.
 */
class LlmMetricsTest {

    private SimpleMeterRegistry registry;
    private LlmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LlmMetrics(registry);
    }

    // =========================================================================
    // Request counter
    // =========================================================================

    @Test
    void incrementRequests_registersCounterWithStatusAndModelTags() {
        metrics.incrementRequests(LlmMetrics.STATUS_SUCCESS, "gpt-4o");

        Counter counter = registry.find(LlmMetrics.METRIC_REQUESTS)
                .tag(LlmMetrics.TAG_STATUS, LlmMetrics.STATUS_SUCCESS)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .counter();

        assertNotNull(counter, "Counter must be registered");
        assertEquals(1.0, counter.count(), 0.001, "Counter must be incremented once");
    }

    @Test
    void incrementRequests_tracksSuccessAndFailureSeparately() {
        metrics.incrementRequests(LlmMetrics.STATUS_SUCCESS, "gpt-4o");
        metrics.incrementRequests(LlmMetrics.STATUS_SUCCESS, "gpt-4o");
        metrics.incrementRequests(LlmMetrics.STATUS_FAILURE, "gpt-4o");

        double successCount = registry.find(LlmMetrics.METRIC_REQUESTS)
                .tag(LlmMetrics.TAG_STATUS, LlmMetrics.STATUS_SUCCESS)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .counter().count();

        double failureCount = registry.find(LlmMetrics.METRIC_REQUESTS)
                .tag(LlmMetrics.TAG_STATUS, LlmMetrics.STATUS_FAILURE)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .counter().count();

        assertEquals(2.0, successCount, 0.001, "Success counter must be 2");
        assertEquals(1.0, failureCount, 0.001, "Failure counter must be 1");
    }

    // =========================================================================
    // Latency timer
    // =========================================================================

    @Test
    void startAndStopLatencyTimer_registersTimerWithStatusAndModelTags() {
        // Use MockClock to advance time without Thread.sleep — deterministic and instant
        io.micrometer.core.instrument.MockClock clock = new io.micrometer.core.instrument.MockClock();
        SimpleMeterRegistry clockedRegistry = new SimpleMeterRegistry(
                io.micrometer.core.instrument.simple.SimpleConfig.DEFAULT, clock);
        LlmMetrics clockedMetrics = new LlmMetrics(clockedRegistry);

        Timer.Sample sample = clockedMetrics.startLatencyTimer();
        clock.add(java.time.Duration.ofMillis(100)); // advance clock by 100ms — no actual sleep
        clockedMetrics.stopLatencyTimer(sample, LlmMetrics.STATUS_SUCCESS, "gpt-4o");

        Timer timer = clockedRegistry.find(LlmMetrics.METRIC_LATENCY)
                .tag(LlmMetrics.TAG_STATUS, LlmMetrics.STATUS_SUCCESS)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .timer();

        assertNotNull(timer, "Timer must be registered");
        assertEquals(1, timer.count(), "Timer must record exactly one observation");
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 0,
                "Timer total time must be non-negative");
    }

    // =========================================================================
    // Retry counter
    // =========================================================================

    @Test
    void incrementRetries_registersCounterWithModelTag() {
        metrics.incrementRetries("gpt-4o");
        metrics.incrementRetries("gpt-4o");

        Counter counter = registry.find(LlmMetrics.METRIC_RETRIES)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .counter();

        assertNotNull(counter, "Retry counter must be registered");
        assertEquals(2.0, counter.count(), 0.001, "Retry counter must be 2");
    }

    // =========================================================================
    // Error counter
    // =========================================================================

    @Test
    void incrementErrors_registersCounterWithErrorTypeTag() {
        metrics.incrementErrors(LlmMetrics.ERROR_TIMEOUT);
        metrics.incrementErrors(LlmMetrics.ERROR_TIMEOUT);
        metrics.incrementErrors(LlmMetrics.ERROR_VALIDATION);

        double timeoutCount = registry.find(LlmMetrics.METRIC_ERRORS)
                .tag(LlmMetrics.TAG_ERROR_TYPE, LlmMetrics.ERROR_TIMEOUT)
                .counter().count();

        double validationCount = registry.find(LlmMetrics.METRIC_ERRORS)
                .tag(LlmMetrics.TAG_ERROR_TYPE, LlmMetrics.ERROR_VALIDATION)
                .counter().count();

        assertEquals(2.0, timeoutCount, 0.001, "Timeout error counter must be 2");
        assertEquals(1.0, validationCount, 0.001, "Validation error counter must be 1");
    }

    @Test
    void incrementErrors_supportsAllErrorTypes() {
        // Verify all error type constants can be recorded without throwing
        assertDoesNotThrow(() -> {
            metrics.incrementErrors(LlmMetrics.ERROR_TIMEOUT);
            metrics.incrementErrors(LlmMetrics.ERROR_VALIDATION);
            metrics.incrementErrors(LlmMetrics.ERROR_COMMUNICATION);
            metrics.incrementErrors(LlmMetrics.ERROR_REFUSAL);
            metrics.incrementErrors(LlmMetrics.ERROR_AUTH);
            metrics.incrementErrors(LlmMetrics.ERROR_RATE_LIMIT);
        });
    }

    // =========================================================================
    // Token distribution summaries
    // =========================================================================

    @Test
    void recordTokenUsage_registersAllThreeSummariesWithModelTag() {
        metrics.recordTokenUsage("gpt-4o", 100, 50, 150);

        DistributionSummary promptSummary = registry.find(LlmMetrics.METRIC_TOKENS_PROMPT)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .summary();
        DistributionSummary completionSummary = registry.find(LlmMetrics.METRIC_TOKENS_COMPLETION)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .summary();
        DistributionSummary totalSummary = registry.find(LlmMetrics.METRIC_TOKENS_TOTAL)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .summary();

        assertNotNull(promptSummary, "Prompt token summary must be registered");
        assertNotNull(completionSummary, "Completion token summary must be registered");
        assertNotNull(totalSummary, "Total token summary must be registered");

        assertEquals(100.0, promptSummary.totalAmount(), 0.001, "Prompt tokens must be 100");
        assertEquals(50.0, completionSummary.totalAmount(), 0.001, "Completion tokens must be 50");
        assertEquals(150.0, totalSummary.totalAmount(), 0.001, "Total tokens must be 150");
    }

    @Test
    void recordTokenUsage_accumulatesAcrossMultipleCalls() {
        metrics.recordTokenUsage("gpt-4o", 100, 50, 150);
        metrics.recordTokenUsage("gpt-4o", 200, 80, 280);

        DistributionSummary totalSummary = registry.find(LlmMetrics.METRIC_TOKENS_TOTAL)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .summary();

        assertNotNull(totalSummary);
        assertEquals(2, totalSummary.count(), "Summary must record 2 observations");
        assertEquals(430.0, totalSummary.totalAmount(), 0.001, "Total tokens must be 430 (150 + 280)");
    }

    @Test
    void recordTokenUsage_tracksModelsSeparately() {
        metrics.recordTokenUsage("gpt-4o", 100, 50, 150);
        metrics.recordTokenUsage("gpt-4o-mini", 60, 30, 90);

        double gpt4oTotal = registry.find(LlmMetrics.METRIC_TOKENS_TOTAL)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o")
                .summary().totalAmount();

        double miniTotal = registry.find(LlmMetrics.METRIC_TOKENS_TOTAL)
                .tag(LlmMetrics.TAG_MODEL, "gpt-4o-mini")
                .summary().totalAmount();

        assertEquals(150.0, gpt4oTotal, 0.001, "gpt-4o total tokens must be 150");
        assertEquals(90.0, miniTotal, 0.001, "gpt-4o-mini total tokens must be 90");
    }
}
