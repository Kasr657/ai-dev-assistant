package com.aidevassistant.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.PostConstruct;

/**
 * Externalized configuration for the LLM (OpenAI) provider.
 *
 * <p>All properties are bound from the {@code llm.openai.*} namespace and can be
 * overridden via environment variables using Spring Boot's relaxed binding rules.
 * For example, {@code llm.openai.api-key} maps to {@code LLM_OPENAI_API_KEY}.
 *
 * <p>The application will fail to start if:
 * <ul>
 *   <li>{@code llm.openai.api-key} is blank or absent</li>
 *   <li>{@code llm.openai.service-timeout-seconds} is not greater than
 *       {@code llm.openai.timeout-seconds * 2} (must cover two full HTTP call attempts)</li>
 * </ul>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "llm.openai")
public class LlmProperties {

    /**
     * OpenAI API key. Required — no default. Startup fails if blank or absent.
     * Override via environment variable: {@code LLM_OPENAI_API_KEY}
     */
    @NotBlank(message = "llm.openai.api-key must not be blank — set the LLM_OPENAI_API_KEY environment variable")
    private String apiKey;

    /**
     * OpenAI model name to use for chat completions.
     * Override via environment variable: {@code LLM_OPENAI_MODEL}
     * Default: {@code gpt-4o}
     */
    private String model = "gpt-4o";

    /**
     * Maximum number of tokens the LLM may return in a single response.
     * Override via environment variable: {@code LLM_OPENAI_MAX_TOKENS}
     * Default: {@code 1024}
     */
    @Min(value = 1, message = "llm.openai.max-tokens must be at least 1")
    private int maxTokens = 1024;

    /**
     * HTTP client timeout in seconds for a single OpenAI API call.
     * Override via environment variable: {@code LLM_OPENAI_TIMEOUT_SECONDS}
     * Default: {@code 30}
     */
    @Min(value = 1, message = "llm.openai.timeout-seconds must be at least 1")
    private int timeoutSeconds = 30;

    /**
     * Service-level deadline in seconds for the full analysis pipeline
     * (prompt build + LLM call + validation, including one retry).
     * Must be greater than {@code timeoutSeconds * 2} to cover one retry.
     * Override via environment variable: {@code LLM_OPENAI_SERVICE_TIMEOUT_SECONDS}
     * Default: {@code 70}
     */
    @Min(value = 1, message = "llm.openai.service-timeout-seconds must be at least 1")
    private int serviceTimeoutSeconds = 70;

    /**
     * OpenAI Chat Completions API path, relative to the base URL.
     * Default value is supplied via {@code application.properties} (llm.openai.chat-path).
     * Override via environment variable: {@code LLM_OPENAI_CHAT_PATH}
     */
    private String chatPath;

    /**
     * Maximum allowed prompt length in characters before the request is rejected.
     * Prevents excessively large code snippets from consuming the entire token budget.
     * Approximately 12,000 characters ≈ 3,000 tokens, well within GPT-4o's context window.
     * Override via environment variable: {@code LLM_OPENAI_MAX_PROMPT_CHARS}
     * Default: {@code 12000}
     */
    @Min(value = 100, message = "llm.openai.max-prompt-chars must be at least 100")
    private int maxPromptChars = 12000;

    /**
     * Validates cross-field constraints that cannot be expressed with single-field annotations.
     *
     * <p>Enforces that {@code serviceTimeoutSeconds > timeoutSeconds * 2} so the service-level
     * deadline is always large enough to accommodate two full HTTP call attempts (initial + retry)
     * before firing. If this constraint is violated the application fails to start with a
     * descriptive error message.
     */
    @PostConstruct
    public void validate() {
        if (serviceTimeoutSeconds <= timeoutSeconds * 2) {
            throw new IllegalStateException(String.format(
                    "Invalid LLM configuration: llm.openai.service-timeout-seconds (%d) must be greater than " +
                    "llm.openai.timeout-seconds * 2 (%d). " +
                    "The service deadline must cover two full HTTP call attempts (initial + one retry). " +
                    "Increase LLM_OPENAI_SERVICE_TIMEOUT_SECONDS or decrease LLM_OPENAI_TIMEOUT_SECONDS.",
                    serviceTimeoutSeconds, timeoutSeconds * 2));
        }
    }
}
