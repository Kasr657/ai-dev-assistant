package com.aidevassistant.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Centralises all Micrometer metric definitions for the LLM analysis pipeline.
 *
 * <p>All meters are pre-registered in the constructor against the default model tag
 * ({@code gpt-4o}). Micrometer's {@code register()} is idempotent — calling it with
 * the same name and tags always returns the same meter instance from the registry's
 * internal {@link java.util.concurrent.ConcurrentHashMap}. Pre-registering avoids
 * that map lookup on every hot-path call.
 *
 * <p>For models other than the default, meters are registered lazily on first use
 * (still a single lookup per unique tag combination, not per request).
 *
 * <h3>Metrics exposed</h3>
 * <table border="1">
 *   <tr><th>Metric</th><th>Type</th><th>Tags</th><th>Description</th></tr>
 *   <tr><td>{@code llm.analysis.requests}</td><td>Counter</td>
 *       <td>status, model</td><td>Total analysis requests by outcome</td></tr>
 *   <tr><td>{@code llm.analysis.latency}</td><td>Timer</td>
 *       <td>status, model</td><td>End-to-end analysis latency</td></tr>
 *   <tr><td>{@code llm.retries}</td><td>Counter</td>
 *       <td>model</td><td>Number of LLM call retries due to validation failure</td></tr>
 *   <tr><td>{@code llm.errors}</td><td>Counter</td>
 *       <td>error_type</td><td>LLM errors by type (timeout, validation, etc.)</td></tr>
 *   <tr><td>{@code llm.tokens.prompt}</td><td>DistributionSummary</td>
 *       <td>model</td><td>Prompt token count per request</td></tr>
 *   <tr><td>{@code llm.tokens.completion}</td><td>DistributionSummary</td>
 *       <td>model</td><td>Completion token count per request</td></tr>
 *   <tr><td>{@code llm.tokens.total}</td><td>DistributionSummary</td>
 *       <td>model</td><td>Total token count per request</td></tr>
 * </table>
 */
@Component
public class LlmMetrics {

    // -------------------------------------------------------------------------
    // Metric names
    // -------------------------------------------------------------------------

    public static final String METRIC_REQUESTS          = "llm.analysis.requests";
    public static final String METRIC_LATENCY           = "llm.analysis.latency";
    public static final String METRIC_RETRIES           = "llm.retries";
    public static final String METRIC_ERRORS            = "llm.errors";
    public static final String METRIC_TOKENS_PROMPT     = "llm.tokens.prompt";
    public static final String METRIC_TOKENS_COMPLETION = "llm.tokens.completion";
    public static final String METRIC_TOKENS_TOTAL      = "llm.tokens.total";

    // -------------------------------------------------------------------------
    // Tag keys
    // -------------------------------------------------------------------------

    public static final String TAG_STATUS     = "status";
    public static final String TAG_MODEL      = "model";
    public static final String TAG_ERROR_TYPE = "error_type";

    // -------------------------------------------------------------------------
    // Tag values — status
    // -------------------------------------------------------------------------

    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILURE = "failure";

    // -------------------------------------------------------------------------
    // Tag values — error_type
    // -------------------------------------------------------------------------

    public static final String ERROR_TIMEOUT       = "timeout";
    public static final String ERROR_VALIDATION    = "validation";
    public static final String ERROR_COMMUNICATION = "communication";
    public static final String ERROR_REFUSAL       = "refusal";
    public static final String ERROR_AUTH          = "auth";
    public static final String ERROR_RATE_LIMIT    = "rate_limit";
    public static final String ERROR_CIRCUIT_OPEN  = "circuit_open";

    // -------------------------------------------------------------------------
    // Registry — kept for lazy registration of non-default model tags
    // -------------------------------------------------------------------------

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Pre-warm meters for the default model so the first real request
        // does not pay the ConcurrentHashMap insertion cost.
        preRegister("gpt-4o");
    }

    /**
     * Pre-registers all meters for the given model tag so subsequent calls
     * return the cached instance without a map insertion.
     */
    private void preRegister(String model) {
        // Request counters
        requestCounter(STATUS_SUCCESS, model);
        requestCounter(STATUS_FAILURE, model);

        // Latency timers
        latencyTimer(STATUS_SUCCESS, model);
        latencyTimer(STATUS_FAILURE, model);

        // Retry counter
        retryCounter(model);

        // Error counters
        for (String errorType : new String[]{
                ERROR_TIMEOUT, ERROR_VALIDATION, ERROR_COMMUNICATION,
                ERROR_REFUSAL, ERROR_AUTH, ERROR_RATE_LIMIT, ERROR_CIRCUIT_OPEN}) {
            errorCounter(errorType);
        }

        // Token summaries
        tokenSummary(METRIC_TOKENS_PROMPT, model);
        tokenSummary(METRIC_TOKENS_COMPLETION, model);
        tokenSummary(METRIC_TOKENS_TOTAL, model);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Increments the request counter for the given status and model.
     *
     * @param status one of {@link #STATUS_SUCCESS} or {@link #STATUS_FAILURE}
     * @param model  the LLM model name (e.g. {@code gpt-4o})
     */
    public void incrementRequests(String status, String model) {
        requestCounter(status, model).increment();
    }

    /**
     * Returns a {@link Timer.Sample} that should be stopped via
     * {@link #stopLatencyTimer(Timer.Sample, String, String)} when the request completes.
     */
    public Timer.Sample startLatencyTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops the given timer sample and records the elapsed time.
     *
     * @param sample the sample returned by {@link #startLatencyTimer()}
     * @param status one of {@link #STATUS_SUCCESS} or {@link #STATUS_FAILURE}
     * @param model  the LLM model name
     */
    public void stopLatencyTimer(Timer.Sample sample, String status, String model) {
        sample.stop(latencyTimer(status, model));
    }

    /**
     * Increments the retry counter for the given model.
     *
     * @param model the LLM model name
     */
    public void incrementRetries(String model) {
        retryCounter(model).increment();
    }

    /**
     * Increments the error counter for the given error type.
     *
     * @param errorType one of the {@code ERROR_*} constants defined in this class
     */
    public void incrementErrors(String errorType) {
        errorCounter(errorType).increment();
    }

    /**
     * Records token usage for a single LLM call.
     *
     * @param model            the LLM model name
     * @param promptTokens     number of tokens in the prompt
     * @param completionTokens number of tokens in the completion
     * @param totalTokens      total tokens consumed
     */
    public void recordTokenUsage(String model, int promptTokens, int completionTokens, int totalTokens) {
        tokenSummary(METRIC_TOKENS_PROMPT, model).record(promptTokens);
        tokenSummary(METRIC_TOKENS_COMPLETION, model).record(completionTokens);
        tokenSummary(METRIC_TOKENS_TOTAL, model).record(totalTokens);
    }

    // -------------------------------------------------------------------------
    // Private meter builders — idempotent, return cached instance after first call
    // -------------------------------------------------------------------------

    private Counter requestCounter(String status, String model) {
        return Counter.builder(METRIC_REQUESTS)
                .description("Total LLM analysis requests by outcome")
                .tag(TAG_STATUS, status)
                .tag(TAG_MODEL, model)
                .register(registry);
    }

    private Timer latencyTimer(String status, String model) {
        return Timer.builder(METRIC_LATENCY)
                .description("End-to-end LLM analysis latency")
                .tag(TAG_STATUS, status)
                .tag(TAG_MODEL, model)
                .register(registry);
    }

    private Counter retryCounter(String model) {
        return Counter.builder(METRIC_RETRIES)
                .description("Number of LLM call retries due to validation failure")
                .tag(TAG_MODEL, model)
                .register(registry);
    }

    private Counter errorCounter(String errorType) {
        return Counter.builder(METRIC_ERRORS)
                .description("LLM errors by type")
                .tag(TAG_ERROR_TYPE, errorType)
                .register(registry);
    }

    private DistributionSummary tokenSummary(String metricName, String model) {
        return DistributionSummary.builder(metricName)
                .description(metricName + " per LLM request")
                .tag(TAG_MODEL, model)
                .register(registry);
    }
}
