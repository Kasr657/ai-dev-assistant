package com.aidevassistant.exception;

import com.aidevassistant.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Centralized exception handler that maps application exceptions to structured HTTP error responses.
 *
 * <p>Every error response includes:
 * <ul>
 *   <li>{@code error} — the exception class name</li>
 *   <li>{@code message} — a human-readable description</li>
 *   <li>{@code requestId} — the request ID for log correlation</li>
 * </ul>
 *
 * <p>The {@code requestId} is resolved from MDC first (set by {@code RequestIdFilter}).
 * If MDC has been cleared by the reactive pipeline before the exception reaches this handler,
 * the {@code X-Request-Id} request header is used as a fallback.
 *
 * <p>HTTP status mapping:
 * <ul>
 *   <li>{@link PromptTooLargeException} → 400 Bad Request</li>
 *   <li>{@link RateLimitExceededException} → 429 Too Many Requests (+ Retry-After: 1)</li>
 *   <li>{@link LlmRefusalException} → 422 Unprocessable Content (RFC 9110)</li>
 *   <li>{@link LlmCircuitOpenException} → 503 Service Unavailable</li>
 *   <li>{@link LlmTimeoutException} → 504 Gateway Timeout</li>
 *   <li>{@link LlmRateLimitException} → 429 Too Many Requests</li>
 *   <li>{@link LlmAuthException} → 500 Internal Server Error (misconfigured API key)</li>
 *   <li>{@link LlmValidationException} → 502 Bad Gateway</li>
 *   <li>{@link LlmResponseFailureException} → 502 Bad Gateway</li>
 *   <li>{@link LlmCommunicationException} → 500 Internal Server Error</li>
 *   <li>{@link MethodArgumentNotValidException} → 400 Bad Request</li>
 *   <li>All other {@link Exception} → 500 Internal Server Error</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String MDC_REQUEST_ID_KEY = "requestId";
    private static final String REQUEST_ID_HEADER  = "X-Request-Id";

    @ExceptionHandler(PromptTooLargeException.class)
    public ResponseEntity<ErrorResponse> handlePromptTooLarge(
            PromptTooLargeException ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Global rate limit exceeded — token bucket is empty.
     * Returns HTTP 429 with a {@code Retry-After: 1} header.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                ex.getClass().getSimpleName(),
                ex.getMessage(),
                resolveRequestId(request)
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(body);
    }

    /** Model refused to respond due to content policy or safety filters. */
    @ExceptionHandler(LlmRefusalException.class)
    public ResponseEntity<ErrorResponse> handleLlmRefusal(
            LlmRefusalException ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    /** Circuit breaker is open — too many recent LLM failures. Retry after ~30s. */
    @ExceptionHandler(LlmCircuitOpenException.class)
    public ResponseEntity<ErrorResponse> handleLlmCircuitOpen(
            LlmCircuitOpenException ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.SERVICE_UNAVAILABLE, request);
    }

    @ExceptionHandler(LlmTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleLlmTimeout(
            LlmTimeoutException ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.GATEWAY_TIMEOUT, request);
    }

    /** OpenAI rate limit exceeded — caller should back off before retrying. */
    @ExceptionHandler(LlmRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleLlmRateLimit(
            LlmRateLimitException ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.TOO_MANY_REQUESTS, request);
    }

    /**
     * Authentication failure — the configured API key is invalid or missing.
     * Returns HTTP 500 because this is an operator/configuration error, not a client error.
     */
    @ExceptionHandler(LlmAuthException.class)
    public ResponseEntity<ErrorResponse> handleLlmAuth(
            LlmAuthException ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @ExceptionHandler(LlmValidationException.class)
    public ResponseEntity<ErrorResponse> handleLlmValidation(
            LlmValidationException ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.BAD_GATEWAY, request);
    }

    @ExceptionHandler(LlmResponseFailureException.class)
    public ResponseEntity<ErrorResponse> handleLlmResponseFailure(
            LlmResponseFailureException ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.BAD_GATEWAY, request);
    }

    /**
     * Handles Bean Validation failures on request bodies (e.g. blank {@code code} or {@code language}).
     * Collects all field-level error messages into a single comma-separated string.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));

        String message = fieldErrors.isEmpty() ? ex.getMessage() : fieldErrors;

        ErrorResponse body = new ErrorResponse(
                ex.getClass().getSimpleName(),
                message,
                resolveRequestId(request)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Catch-all for any unhandled exception. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex, HttpServletRequest request) {
        return buildResponse(ex, HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> buildResponse(
            Exception ex, HttpStatus status, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                ex.getClass().getSimpleName(),
                ex.getMessage() != null ? ex.getMessage() : "",
                resolveRequestId(request)
        );
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Resolves the request ID for inclusion in error responses.
     *
     * <p>Tries MDC first (populated by {@code RequestIdFilter} for the duration of the
     * request). Falls back to the {@code X-Request-Id} request header if MDC has been
     * cleared (e.g. by the reactive pipeline's {@code doOnTerminate(MDC::clear)} before
     * the exception propagates to this handler).
     */
    private String resolveRequestId(HttpServletRequest request) {
        String fromMdc = MDC.get(MDC_REQUEST_ID_KEY);
        if (fromMdc != null) return fromMdc;
        String fromHeader = request.getHeader(REQUEST_ID_HEADER);
        return fromHeader != null ? fromHeader : "unknown";
    }
}
