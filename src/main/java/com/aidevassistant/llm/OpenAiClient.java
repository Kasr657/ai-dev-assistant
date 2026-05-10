package com.aidevassistant.llm;

import com.aidevassistant.config.LlmMetrics;
import com.aidevassistant.config.LlmProperties;
import com.aidevassistant.exception.LlmAuthException;
import com.aidevassistant.exception.LlmCommunicationException;
import com.aidevassistant.exception.LlmRateLimitException;
import com.aidevassistant.exception.LlmRefusalException;
import com.aidevassistant.exception.LlmTimeoutException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI implementation of {@link LlmClient}.
 *
 * <p>Sends a single user message to the OpenAI Chat Completions API using a fully
 * reactive WebClient pipeline and returns a {@link Mono} that emits the content
 * string from the first choice.
 *
 * <h3>Structured output enforcement</h3>
 * <p>Every request uses {@code response_format.type = "json_schema"} with the full
 * {@code AnalysisResponse} schema (built by {@link AnalysisResponseSchemaFactory}).
 * This is OpenAI's strict structured output mode — the model is constrained to produce
 * JSON that exactly matches the declared schema, eliminating free-text responses and
 * hallucinated field names. Supported on {@code gpt-4o} (2024-08-06 and later) and
 * {@code gpt-4o-mini}.
 *
 * <h3>Refusal handling</h3>
 * <p>When the model declines to respond (e.g. due to content policy), OpenAI sets
 * {@code choices[0].message.refusal} instead of {@code content}. This is detected
 * and surfaced as a {@link LlmRefusalException} (HTTP 422).
 *
 * <h3>Token usage observability</h3>
 * <p>Token usage ({@code prompt_tokens}, {@code completion_tokens}, {@code total_tokens})
 * is extracted from the response, logged at DEBUG level, and recorded to Micrometer
 * distribution summaries ({@code llm.tokens.prompt}, {@code llm.tokens.completion},
 * {@code llm.tokens.total}) tagged by model name. The parsed {@link TokenUsage} is also
 * accessible via {@link #parseTokenUsage(JsonNode)} for testing.
 *
 * <h3>Validator still required</h3>
 * <p>Even with {@code json_schema} strict mode, the downstream {@code LlmResponseValidator}
 * must remain in place. Providers can still return malformed payloads, partial responses,
 * safety refusals, or change behaviour across API versions. The validator is the last
 * line of defence before untrusted LLM output reaches the application.
 *
 * <h3>HTTP error mapping</h3>
 * <ul>
 *   <li>401 Unauthorized → {@link LlmAuthException} — invalid or missing API key</li>
 *   <li>429 Too Many Requests → {@link LlmRateLimitException} — rate limit exceeded</li>
 *   <li>Response timeout → {@link LlmTimeoutException}</li>
 *   <li>All other HTTP errors or network failures → {@link LlmCommunicationException}</li>
 * </ul>
 *
 * <p>The {@code openAiWebClient} bean (configured in {@code WebClientConfig})
 * provides the base URL and {@code Authorization: Bearer} header.
 */
@Component
public class OpenAiClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    private final LlmProperties props;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AnalysisResponseSchemaFactory schemaFactory;
    private final LlmMetrics llmMetrics;

    /**
     * @param props         externalized LLM configuration (model, tokens, timeouts)
     * @param webClient     pre-configured WebClient bean with base URL and auth header
     * @param objectMapper  shared Jackson mapper for request serialization and response parsing
     * @param schemaFactory builds the {@code response_format} block for structured output
     * @param llmMetrics    Micrometer metrics for token usage recording
     */
    public OpenAiClient(LlmProperties props,
                        @Qualifier("openAiWebClient") WebClient webClient,
                        ObjectMapper objectMapper,
                        AnalysisResponseSchemaFactory schemaFactory,
                        LlmMetrics llmMetrics) {
        this.props = props;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.schemaFactory = schemaFactory;
        this.llmMetrics = llmMetrics;
    }

    /**
     * Sends the prompt to the OpenAI Chat Completions endpoint and returns a
     * {@link Mono} that emits the content string from the first choice.
     *
     * <p>The reactive pipeline applies a per-call HTTP timeout via
     * {@link Mono#timeout(Duration)}. Because the timeout is applied on the reactive
     * chain itself, a deadline cancels the underlying HTTP request — no in-flight
     * connections are leaked.
     *
     * @param prompt the fully constructed prompt; must not be blank
     * @return a {@link Mono} emitting the raw content string from the LLM
     * @throws LlmRefusalException if the model refuses to respond due to content policy
     */
    @Override
    public Mono<String> call(String prompt) {
        String requestBody = buildRequestBody(prompt);

        return webClient.post()
                .uri(props.getChatPath())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .onErrorMap(TimeoutException.class, ex ->
                        new LlmTimeoutException(
                                "OpenAI API did not respond within "
                                        + props.getTimeoutSeconds() + " seconds", ex))
                .onErrorMap(WebClientResponseException.class, this::mapHttpError)
                .onErrorMap(ex -> !(ex instanceof LlmTimeoutException)
                                && !(ex instanceof LlmAuthException)
                                && !(ex instanceof LlmRateLimitException)
                                && !(ex instanceof LlmCommunicationException),
                        ex -> new LlmCommunicationException(
                                "Failed to communicate with OpenAI API: " + ex.getMessage(), ex))
                .map(this::extractAndLogContent);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a {@link WebClientResponseException} to the appropriate typed exception
     * based on the HTTP status code.
     */
    private RuntimeException mapHttpError(WebClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == HttpStatus.UNAUTHORIZED.value()) {
            return new LlmAuthException(
                    "OpenAI API key is invalid or missing (HTTP 401). "
                            + "Check the LLM_OPENAI_API_KEY environment variable.", ex);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new LlmRateLimitException(
                    "OpenAI API rate limit exceeded (HTTP 429). "
                            + "Back off and retry later.", ex);
        }
        return new LlmCommunicationException(
                "OpenAI API returned HTTP " + status + ": " + ex.getMessage(), ex);
    }

    /**
     * Builds the JSON request body for the OpenAI Chat Completions API.
     *
     * <p>Delegates schema construction to {@link AnalysisResponseSchemaFactory}.
     * Temperature is fixed at {@code 0.2}; model and max_tokens are sourced
     * from {@link LlmProperties}.
     */
    private String buildRequestBody(String prompt) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.set("model", new TextNode(props.getModel()));
            root.set("temperature", new DoubleNode(0.2));
            root.set("max_tokens", new IntNode(props.getMaxTokens()));

            // Delegate response_format construction to the schema factory
            schemaFactory.addResponseFormat(root);

            ArrayNode messages = root.putArray("messages");
            ObjectNode userMessage = messages.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new LlmCommunicationException(
                    "Failed to serialize OpenAI request body: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the {@code content} string from the first choice in the OpenAI response.
     *
     * <p>Checks for a {@code refusal} field before reading {@code content}. If the model
     * declined to respond, throws {@link LlmRefusalException} with the refusal reason.
     *
     * <p>Logs token usage at DEBUG level after a successful extraction.
     *
     * @param responseBody the raw JSON response string from OpenAI
     * @return the content string
     * @throws LlmRefusalException       if the model refused to respond
     * @throws LlmCommunicationException if the response cannot be parsed or expected fields are absent
     */
    private String extractAndLogContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Log token usage at DEBUG and record to Micrometer metrics
            TokenUsage usage = parseTokenUsage(root);
            if (usage.isAvailable()) {
                log.debug("OpenAI token usage — model: {}, prompt_tokens: {}, completion_tokens: {}, total_tokens: {}",
                        usage.model(), usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
                llmMetrics.recordTokenUsage(
                        usage.model(),
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.totalTokens());
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmCommunicationException(
                        "OpenAI response contained no choices. Response: "
                                + abbreviate(responseBody));
            }

            JsonNode firstChoice = choices.get(0);
            if (!firstChoice.isObject()) {
                throw new LlmCommunicationException(
                        "OpenAI response choices[0] is not an object. Response: "
                                + abbreviate(responseBody));
            }

            JsonNode message = firstChoice.path("message");
            if (message.isMissingNode() || !message.isObject()) {
                throw new LlmCommunicationException(
                        "OpenAI response choices[0].message is missing. Response: "
                                + abbreviate(responseBody));
            }

            // Check for model refusal before reading content.
            // OpenAI sets choices[0].message.refusal when the model declines to respond
            // (e.g. content policy, safety filters). This is distinct from an error response.
            JsonNode refusal = message.path("refusal");
            if (!refusal.isMissingNode() && !refusal.isNull() && refusal.isTextual()) {
                String refusalText = refusal.asText();
                log.warn("OpenAI model refused to respond: {}", refusalText);
                throw new LlmRefusalException(refusalText);
            }

            JsonNode content = message.path("content");
            if (content.isMissingNode() || content.isNull() || !content.isTextual()) {
                throw new LlmCommunicationException(
                        "OpenAI response choices[0].message.content is missing or not a string. Response: "
                                + abbreviate(responseBody));
            }

            return content.asText();

        } catch (LlmRefusalException | LlmCommunicationException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmCommunicationException(
                    "Failed to parse OpenAI response: " + e.getMessage(), e);
        }
    }

    /**
     * Parses token usage from the {@code usage} field of the OpenAI response root node.
     *
     * <p>Package-visible to allow direct assertion in unit tests without relying on
     * log output capture.
     *
     * @param root the parsed JSON root node of the OpenAI response
     * @return a {@link TokenUsage} with the parsed values, or {@link TokenUsage#UNAVAILABLE}
     *         if the {@code usage} field is absent
     */
    TokenUsage parseTokenUsage(JsonNode root) {
        JsonNode usageNode = root.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return TokenUsage.UNAVAILABLE;
        }
        return new TokenUsage(
                root.path("model").asText(props.getModel()),
                usageNode.path("prompt_tokens").asInt(0),
                usageNode.path("completion_tokens").asInt(0),
                usageNode.path("total_tokens").asInt(0)
        );
    }

    /** Returns the first 200 characters of a string for use in error messages. */
    private static String abbreviate(String s) {
        if (s == null) return "(null)";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...[truncated]";
    }
}
