package com.aidevassistant.service;

import com.aidevassistant.config.LlmCircuitBreakerConfig;
import com.aidevassistant.config.LlmMetrics;
import com.aidevassistant.config.LlmProperties;
import com.aidevassistant.dto.AnalysisRequest;
import com.aidevassistant.dto.AnalysisResponse;
import com.aidevassistant.exception.LlmAuthException;
import com.aidevassistant.exception.LlmCircuitOpenException;
import com.aidevassistant.exception.LlmCommunicationException;
import com.aidevassistant.exception.LlmRateLimitException;
import com.aidevassistant.exception.LlmRefusalException;
import com.aidevassistant.exception.LlmResponseFailureException;
import com.aidevassistant.exception.LlmTimeoutException;
import com.aidevassistant.exception.LlmValidationException;
import com.aidevassistant.llm.LlmClient;
import com.aidevassistant.prompt.PromptBuilder;
import com.aidevassistant.validator.LlmResponseValidator;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Orchestrates the full code analysis pipeline:
 * {@link PromptBuilder} → {@link LlmClient} → {@link LlmResponseValidator}.
 *
 * <p><strong>Reactive pipeline:</strong> The LLM call is made via a fully reactive
 * WebClient chain. The service-level deadline is applied using {@link Mono#timeout(Duration)}
 * directly on the reactive chain, which cancels the underlying HTTP request when the
 * deadline is exceeded — no in-flight connections are leaked.
 *
 * <p><strong>Circuit breaker:</strong> All LLM calls are wrapped with a Resilience4j
 * circuit breaker ({@code llm-openai}). When the circuit is open (too many recent
 * failures), calls fail immediately with {@link LlmCircuitOpenException} (HTTP 503)
 * rather than waiting for a timeout. The circuit resets automatically after 30 seconds.
 *
 * <p><strong>Retry policy:</strong> On the first {@link LlmValidationException} the
 * call is retried once with the same prompt. A second validation failure throws
 * {@link LlmResponseFailureException}. {@link LlmTimeoutException} and
 * {@link LlmCommunicationException} are never retried and propagate immediately.
 *
 * <p><strong>MDC propagation:</strong> The {@code requestId} from the inbound request
 * thread is captured before the reactive subscription and re-applied inside the
 * reactive pipeline so that all log entries carry the correct request ID.
 *
 * <p><strong>Metrics:</strong> Every call records request count, latency, retry count,
 * and error type via {@link LlmMetrics}.
 */
@Service
public class CodeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalysisService.class);

    /** Maximum number of characters of the raw LLM response to include in debug logs. */
    private static final int MAX_LOG_RESPONSE_LENGTH = 500;

    /**
     * Matches the code block between the prompt delimiters so it can be redacted
     * before logging. The {@code DOTALL} flag is required because code spans multiple lines.
     */
    private static final Pattern CODE_BLOCK_PATTERN =
            Pattern.compile("(--- BEGIN CODE ---)(.*?)(--- END CODE ---)", Pattern.DOTALL);

    private final PromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final LlmResponseValidator validator;
    private final LlmProperties llmProperties;
    private final LlmMetrics llmMetrics;
    private final CircuitBreaker circuitBreaker;

    public CodeAnalysisService(PromptBuilder promptBuilder,
                               LlmClient llmClient,
                               LlmResponseValidator validator,
                               LlmProperties llmProperties,
                               LlmMetrics llmMetrics,
                               CircuitBreaker llmCircuitBreaker) {
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.validator = validator;
        this.llmProperties = llmProperties;
        this.llmMetrics = llmMetrics;
        this.circuitBreaker = llmCircuitBreaker;
    }

    /**
     * Analyzes the code snippet in {@code request} and returns structured insights.
     *
     * <p>The entire pipeline (prompt build + up to 2 LLM calls + validation) is
     * bounded by the service-level deadline configured in
     * {@link LlmProperties#getServiceTimeoutSeconds()}. The deadline is applied via
     * {@link Mono#timeout(Duration)} on the reactive chain, which cancels the
     * underlying HTTP request if the deadline is exceeded — preventing connection leaks.
     *
     * @param request the analysis request; must not be {@code null}
     * @return a validated {@link AnalysisResponse}
     * @throws LlmTimeoutException          if the service-level deadline is exceeded
     * @throws LlmCommunicationException    if a network or HTTP error occurs
     * @throws LlmResponseFailureException  if validation fails on both attempts
     */
    public AnalysisResponse analyze(AnalysisRequest request) {
        String requestId = MDC.get("requestId");
        String model = llmProperties.getModel();

        String prompt = promptBuilder.build(request);

        if (log.isDebugEnabled()) {
            log.debug("Built prompt (sanitized): {}", sanitizePrompt(prompt));
        }

        Duration deadline = Duration.ofSeconds(llmProperties.getServiceTimeoutSeconds());
        Timer.Sample latencySample = llmMetrics.startLatencyTimer();

        try {
            AnalysisResponse result = buildPipeline(prompt, requestId, model)
                    .timeout(deadline)
                    .onErrorMap(TimeoutException.class, ex ->
                            new LlmTimeoutException(
                                    "Service-level deadline of "
                                            + llmProperties.getServiceTimeoutSeconds() + "s exceeded"))
                    .block();

            recordOutcome(latencySample, LlmMetrics.STATUS_SUCCESS, model, null);
            return result;

        } catch (RuntimeException ex) {
            recordOutcome(latencySample, LlmMetrics.STATUS_FAILURE, model, ex);
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the reactive pipeline for a single analysis attempt with one retry
     * on {@link LlmValidationException}.
     *
     * <p>The MDC {@code requestId} is restored at the start of the reactive chain
     * so that log entries emitted inside the pipeline carry the correct request ID.
     *
     * <p>{@link LlmTimeoutException} and {@link LlmCommunicationException} propagate
     * immediately without retry.
     */
    private Mono<AnalysisResponse> buildPipeline(String prompt, String requestId, String model) {
        return llmClient.call(prompt)
                // Apply circuit breaker — fails fast with LlmCircuitOpenException when open
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(CallNotPermittedException.class, ex ->
                        new LlmCircuitOpenException(
                                "LLM circuit breaker is open — too many recent failures. "
                                        + "Retry after " + LlmCircuitBreakerConfig.CIRCUIT_BREAKER_NAME
                                        + " circuit resets (30s).", ex))
                .doOnSubscribe(s -> {
                    if (requestId != null) {
                        MDC.put("requestId", requestId);
                    }
                })
                .doOnTerminate(MDC::clear)
                .flatMap(raw -> {
                    if (log.isDebugEnabled()) {
                        log.debug("Raw LLM response (first {} chars): {}",
                                MAX_LOG_RESPONSE_LENGTH, truncate(raw));
                    }
                    // Offload synchronous JSON parsing to boundedElastic so it does
                    // not block the Netty event loop thread.
                    return Mono.fromCallable(() -> validator.validate(raw))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .doOnNext(result -> log.debug("Parsed AnalysisResponse: {}", result))
                .onErrorResume(LlmValidationException.class, firstError -> {
                    log.warn("LLM validation failed on attempt 1, retrying. Reason: {}",
                            firstError.getMessage());
                    llmMetrics.incrementRetries(model);
                    return llmClient.call(prompt)
                            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                            .onErrorMap(CallNotPermittedException.class, ex ->
                                    new LlmCircuitOpenException(
                                            "LLM circuit breaker is open on retry.", ex))
                            .flatMap(raw -> {
                                if (log.isDebugEnabled()) {
                                    log.debug("Raw LLM response retry (first {} chars): {}",
                                            MAX_LOG_RESPONSE_LENGTH, truncate(raw));
                                }
                                return Mono.fromCallable(() -> validator.validate(raw))
                                        .subscribeOn(Schedulers.boundedElastic());
                            })
                            .doOnNext(result -> log.debug("Parsed AnalysisResponse (retry): {}", result))
                            .onErrorMap(LlmValidationException.class, retryError -> {
                                log.warn("LLM validation failed on attempt 2 (final). Reason: {}",
                                        retryError.getMessage());
                                return new LlmResponseFailureException(
                                        "LLM response validation failed after 2 attempts: "
                                                + retryError.getMessage(),
                                        retryError);
                            });
                })
                .onErrorMap(LlmTimeoutException.class, ex -> {
                    log.error("LLM timeout [requestId={}]: {}", requestId, ex.getMessage());
                    return ex;
                })
                .onErrorMap(LlmCommunicationException.class, ex -> {
                    log.error("LLM communication error [requestId={}]: {}", requestId, ex.getMessage());
                    return ex;
                });
    }

    /**
     * Records the outcome of an analysis call to Micrometer.
     *
     * <p>On success ({@code exception} is {@code null}): records success counter and latency.
     * On failure: records failure counter, latency, and the specific error type counter.
     *
     * <p>Error type mapping:
     * <ul>
     *   <li>{@link LlmTimeoutException} → {@link LlmMetrics#ERROR_TIMEOUT}</li>
     *   <li>{@link LlmRateLimitException} → {@link LlmMetrics#ERROR_RATE_LIMIT}</li>
     *   <li>{@link LlmAuthException} → {@link LlmMetrics#ERROR_AUTH}</li>
     *   <li>{@link LlmRefusalException} → {@link LlmMetrics#ERROR_REFUSAL}</li>
     *   <li>{@link LlmResponseFailureException} / {@link LlmValidationException}
     *       → {@link LlmMetrics#ERROR_VALIDATION}</li>
     *   <li>All others → {@link LlmMetrics#ERROR_COMMUNICATION}</li>
     * </ul>
     */
    private void recordOutcome(Timer.Sample sample, String status, String model, RuntimeException exception) {
        llmMetrics.stopLatencyTimer(sample, status, model);
        llmMetrics.incrementRequests(status, model);

        if (exception != null) {
            llmMetrics.incrementErrors(resolveErrorType(exception));
        }
    }

    /**
     * Maps a runtime exception to the appropriate {@link LlmMetrics} error type constant.
     */
    private static String resolveErrorType(RuntimeException ex) {
        if (ex instanceof LlmTimeoutException)           return LlmMetrics.ERROR_TIMEOUT;
        if (ex instanceof LlmRateLimitException)         return LlmMetrics.ERROR_RATE_LIMIT;
        if (ex instanceof LlmAuthException)              return LlmMetrics.ERROR_AUTH;
        if (ex instanceof LlmRefusalException)           return LlmMetrics.ERROR_REFUSAL;
        if (ex instanceof LlmCircuitOpenException)       return LlmMetrics.ERROR_CIRCUIT_OPEN;
        if (ex instanceof LlmResponseFailureException
                || ex instanceof LlmValidationException) return LlmMetrics.ERROR_VALIDATION;
        return LlmMetrics.ERROR_COMMUNICATION;
    }

    /**
     * Returns a copy of {@code prompt} with the content between the
     * {@code --- BEGIN CODE ---} and {@code --- END CODE ---} delimiters replaced
     * by {@code [REDACTED]}, so that user-supplied code is never written to logs.
     */
    static String sanitizePrompt(String prompt) {
        return CODE_BLOCK_PATTERN.matcher(prompt)
                .replaceFirst("$1\n[REDACTED]\n$3");
    }

    /**
     * Truncates {@code value} to at most {@link #MAX_LOG_RESPONSE_LENGTH} characters,
     * appending {@code "...[truncated]"} if the string was shortened.
     */
    private static String truncate(String value) {
        if (value == null) return "(null)";
        if (value.length() <= MAX_LOG_RESPONSE_LENGTH) return value;
        return value.substring(0, MAX_LOG_RESPONSE_LENGTH) + "...[truncated]";
    }
}
