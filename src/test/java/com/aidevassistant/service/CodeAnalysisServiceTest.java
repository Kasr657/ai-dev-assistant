package com.aidevassistant.service;

import com.aidevassistant.config.LlmMetrics;
import com.aidevassistant.config.LlmProperties;
import com.aidevassistant.dto.AnalysisRequest;
import com.aidevassistant.dto.AnalysisResponse;
import com.aidevassistant.exception.LlmCommunicationException;
import com.aidevassistant.exception.LlmResponseFailureException;
import com.aidevassistant.exception.LlmTimeoutException;
import com.aidevassistant.exception.LlmValidationException;
import com.aidevassistant.llm.LlmClient;
import com.aidevassistant.prompt.PromptBuilder;
import com.aidevassistant.validator.LlmResponseValidator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CodeAnalysisService}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Successful analysis pipeline on the first attempt</li>
 *   <li>Retry on first {@link LlmValidationException}, success on second attempt</li>
 *   <li>{@link LlmResponseFailureException} thrown after two consecutive validation failures</li>
 *   <li>No retry on {@link LlmTimeoutException} — propagates immediately</li>
 *   <li>No retry on {@link LlmCommunicationException} — propagates immediately</li>
 *   <li>Service-level deadline exceeded throws {@link LlmTimeoutException}</li>
 *   <li>Prompt is built exactly once regardless of retry count</li>
 *   <li>{@code sanitizePrompt} replaces code block content with {@code [REDACTED]}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CodeAnalysisServiceTest {

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private LlmClient llmClient;

    @Mock
    private LlmResponseValidator validator;

    private LlmProperties llmProperties;
    private CodeAnalysisService service;

    private static final AnalysisRequest SAMPLE_REQUEST =
            new AnalysisRequest("System.out.println(\"hello\");", "Java");

    private static final String SAMPLE_PROMPT = "You are a senior backend engineer...";

    private static final AnalysisResponse SAMPLE_RESPONSE =
            new AnalysisResponse("Looks good.", List.of("No null checks"), List.of("Add Javadoc"));

    @BeforeEach
    void setUp() {
        llmProperties = new LlmProperties();
        llmProperties.setApiKey("test-key");
        llmProperties.setServiceTimeoutSeconds(10);
        // SimpleMeterRegistry is an in-memory registry — no Prometheus server needed in tests
        LlmMetrics llmMetrics = new LlmMetrics(new SimpleMeterRegistry());
        // Use a CLOSED circuit breaker in tests — it passes all calls through
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test");
        service = new CodeAnalysisService(promptBuilder, llmClient, validator, llmProperties,
                llmMetrics, circuitBreaker);
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    void analyze_returnsResponseOnFirstAttempt() {
        when(promptBuilder.build(SAMPLE_REQUEST)).thenReturn(SAMPLE_PROMPT);
        when(llmClient.call(SAMPLE_PROMPT)).thenReturn(Mono.just("raw-json"));
        when(validator.validate("raw-json")).thenReturn(SAMPLE_RESPONSE);

        AnalysisResponse result = service.analyze(SAMPLE_REQUEST);

        assertSame(SAMPLE_RESPONSE, result);
        verify(llmClient, times(1)).call(SAMPLE_PROMPT);
        verify(validator, times(1)).validate("raw-json");
    }

    // =========================================================================
    // Retry on first LlmValidationException
    // =========================================================================

    @Test
    void analyze_retriesOnceAfterFirstValidationFailure() {
        when(promptBuilder.build(SAMPLE_REQUEST)).thenReturn(SAMPLE_PROMPT);
        when(llmClient.call(SAMPLE_PROMPT))
                .thenReturn(Mono.just("bad-json"))
                .thenReturn(Mono.just("good-json"));
        when(validator.validate("bad-json"))
                .thenThrow(new LlmValidationException("Missing fields"));
        when(validator.validate("good-json")).thenReturn(SAMPLE_RESPONSE);

        AnalysisResponse result = service.analyze(SAMPLE_REQUEST);

        assertSame(SAMPLE_RESPONSE, result);
        verify(llmClient, times(2)).call(SAMPLE_PROMPT);
        verify(validator, times(1)).validate("bad-json");
        verify(validator, times(1)).validate("good-json");
    }

    // =========================================================================
    // LlmResponseFailureException after two validation failures
    // =========================================================================

    @Test
    void analyze_throwsLlmResponseFailureExceptionAfterTwoValidationFailures() {
        when(promptBuilder.build(SAMPLE_REQUEST)).thenReturn(SAMPLE_PROMPT);
        when(llmClient.call(SAMPLE_PROMPT)).thenReturn(Mono.just("bad-json"));
        when(validator.validate(anyString()))
                .thenThrow(new LlmValidationException("Missing fields"));

        assertThrows(LlmResponseFailureException.class,
                () -> service.analyze(SAMPLE_REQUEST));

        verify(llmClient, times(2)).call(SAMPLE_PROMPT);
    }

    // =========================================================================
    // No retry on LlmTimeoutException
    // =========================================================================

    @Test
    void analyze_propagatesLlmTimeoutExceptionWithoutRetry() {
        when(promptBuilder.build(SAMPLE_REQUEST)).thenReturn(SAMPLE_PROMPT);
        when(llmClient.call(SAMPLE_PROMPT))
                .thenReturn(Mono.error(new LlmTimeoutException("HTTP timeout")));

        assertThrows(LlmTimeoutException.class,
                () -> service.analyze(SAMPLE_REQUEST));

        verify(llmClient, times(1)).call(SAMPLE_PROMPT);
    }

    // =========================================================================
    // No retry on LlmCommunicationException
    // =========================================================================

    @Test
    void analyze_propagatesLlmCommunicationExceptionWithoutRetry() {
        when(promptBuilder.build(SAMPLE_REQUEST)).thenReturn(SAMPLE_PROMPT);
        when(llmClient.call(SAMPLE_PROMPT))
                .thenReturn(Mono.error(new LlmCommunicationException("Network error")));

        assertThrows(LlmCommunicationException.class,
                () -> service.analyze(SAMPLE_REQUEST));

        verify(llmClient, times(1)).call(SAMPLE_PROMPT);
    }

    // =========================================================================
    // Service-level timeout
    // =========================================================================

    @Test
    void analyze_throwsLlmTimeoutExceptionWhenServiceDeadlineExceeded() {
        llmProperties.setServiceTimeoutSeconds(1);
        service = new CodeAnalysisService(promptBuilder, llmClient, validator, llmProperties,
                new LlmMetrics(new SimpleMeterRegistry()), CircuitBreaker.ofDefaults("test"));

        when(promptBuilder.build(SAMPLE_REQUEST)).thenReturn(SAMPLE_PROMPT);
        // Simulate a slow LLM response using Mono.delay — the reactive timeout will cancel it
        when(llmClient.call(SAMPLE_PROMPT))
                .thenReturn(Mono.just("raw-json").delayElement(Duration.ofSeconds(5)));

        assertThrows(LlmTimeoutException.class,
                () -> service.analyze(SAMPLE_REQUEST));
    }

    // =========================================================================
    // Prompt is built exactly once regardless of retries
    // =========================================================================

    @Test
    void analyze_buildsPromptExactlyOnce_evenOnRetry() {
        when(promptBuilder.build(SAMPLE_REQUEST)).thenReturn(SAMPLE_PROMPT);
        when(llmClient.call(SAMPLE_PROMPT))
                .thenReturn(Mono.just("bad-json"))
                .thenReturn(Mono.just("good-json"));
        when(validator.validate("bad-json"))
                .thenThrow(new LlmValidationException("Missing fields"));
        when(validator.validate("good-json")).thenReturn(SAMPLE_RESPONSE);

        service.analyze(SAMPLE_REQUEST);

        verify(promptBuilder, times(1)).build(SAMPLE_REQUEST);
    }

    // =========================================================================
    // sanitizePrompt
    // =========================================================================

    @Test
    void sanitizePrompt_replacesCodeBlockWithRedacted() {
        String prompt = "Some preamble\n--- BEGIN CODE ---\npublic class Foo {}\n--- END CODE ---\nSome postamble";
        String sanitized = CodeAnalysisService.sanitizePrompt(prompt);

        assertFalse(sanitized.contains("public class Foo {}"),
                "Sanitized prompt must not contain the original code");
        assertTrue(sanitized.contains("[REDACTED]"),
                "Sanitized prompt must contain [REDACTED] placeholder");
        assertTrue(sanitized.contains("--- BEGIN CODE ---"),
                "Sanitized prompt must retain the BEGIN delimiter");
        assertTrue(sanitized.contains("--- END CODE ---"),
                "Sanitized prompt must retain the END delimiter");
        assertTrue(sanitized.contains("Some preamble"),
                "Sanitized prompt must retain content before the code block");
        assertTrue(sanitized.contains("Some postamble"),
                "Sanitized prompt must retain content after the code block");
    }

    @Test
    void sanitizePrompt_returnsPromptUnchangedWhenNoDelimitersPresent() {
        String prompt = "A prompt with no code delimiters";
        String sanitized = CodeAnalysisService.sanitizePrompt(prompt);
        assertEquals(prompt, sanitized,
                "Prompt without delimiters should be returned unchanged");
    }
}
