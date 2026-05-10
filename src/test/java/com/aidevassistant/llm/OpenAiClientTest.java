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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OpenAiClient} using {@link MockWebServer} to simulate
 * real HTTP interactions without making network calls.
 *
 * <p>Covers:
 * <ul>
 *   <li>Successful response — content extracted correctly from {@code choices[0].message.content}</li>
 *   <li>Structured output — {@code response_format.type = "json_schema"} with full schema in every request</li>
 *   <li>Token usage — {@code prompt_tokens}, {@code completion_tokens}, {@code total_tokens} parsed correctly</li>
 *   <li>HTTP 401 → {@link LlmAuthException}</li>
 *   <li>HTTP 429 → {@link LlmRateLimitException}</li>
 *   <li>HTTP 500 → {@link LlmCommunicationException}</li>
 *   <li>Malformed response (empty choices, missing message, null content, non-JSON) → {@link LlmCommunicationException}</li>
 *   <li>Timeout → {@link LlmTimeoutException}</li>
 * </ul>
 */
class OpenAiClientTest {

    private MockWebServer mockWebServer;
    private OpenAiClient client;
    private LlmProperties props;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        props = new LlmProperties();
        props.setApiKey("test-api-key");
        props.setModel("gpt-4o");
        props.setMaxTokens(512);
        props.setTimeoutSeconds(2); // short timeout for the timeout test

        objectMapper = new ObjectMapper();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        client = new OpenAiClient(props, webClient, objectMapper,
                new AnalysisResponseSchemaFactory(objectMapper),
                new LlmMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // =========================================================================
    // Success path
    // =========================================================================

    /**
     * Verifies that a well-formed OpenAI response is parsed correctly and the
     * content string from {@code choices[0].message.content} is returned.
     */
    @Test
    void call_returnsContentString_onSuccessfulResponse() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(buildSuccessResponse("gpt-4o", "{\"summary\":\"OK\"}", 10, 20, 30)));

        String result = client.call("analyze this code").block();

        assertEquals("{\"summary\":\"OK\"}", result);
    }

    // =========================================================================
    // Structured output — json_schema enforcement
    // =========================================================================

    /**
     * Verifies that every request uses {@code response_format.type = "json_schema"}
     * (strict structured output mode) rather than the weaker {@code json_object} mode.
     */
    @Test
    void call_usesJsonSchemaStructuredOutput() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(buildSuccessResponse("gpt-4o", "{\"summary\":\"OK\"}", 5, 10, 15)));

        client.call("test prompt").block();

        RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request, "Request should have been received");
        JsonNode body = objectMapper.readTree(request.getBody().readUtf8());

        JsonNode responseFormat = body.path("response_format");
        assertFalse(responseFormat.isMissingNode(), "response_format must be present");
        assertEquals("json_schema", responseFormat.path("type").asText(),
                "response_format.type must be json_schema");

        JsonNode jsonSchema = responseFormat.path("json_schema");
        assertFalse(jsonSchema.isMissingNode(), "json_schema block must be present");
        assertEquals("AnalysisResponse", jsonSchema.path("name").asText(),
                "json_schema.name must be AnalysisResponse");
        assertTrue(jsonSchema.path("strict").asBoolean(),
                "json_schema.strict must be true");

        // Verify the schema declares all three required fields
        JsonNode schema = jsonSchema.path("schema");
        JsonNode required = schema.path("required");
        assertTrue(required.isArray(), "schema.required must be an array");
        String requiredStr = required.toString();
        assertTrue(requiredStr.contains("summary"), "schema must require 'summary'");
        assertTrue(requiredStr.contains("issues"), "schema must require 'issues'");
        assertTrue(requiredStr.contains("improvements"), "schema must require 'improvements'");

        // Verify additionalProperties is false (strict mode)
        assertFalse(schema.path("additionalProperties").asBoolean(true),
                "schema.additionalProperties must be false");
    }

    /**
     * Verifies that the model name, temperature, and max_tokens are included in the request.
     */
    @Test
    void call_includesModelAndParametersInRequestBody() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(buildSuccessResponse("gpt-4o", "{\"summary\":\"OK\"}", 5, 10, 15)));

        client.call("test prompt").block();

        RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        JsonNode body = objectMapper.readTree(request.getBody().readUtf8());

        assertEquals("gpt-4o", body.path("model").asText(), "Request must include model name");
        assertEquals(0.2, body.path("temperature").asDouble(), 0.001, "Request must include temperature 0.2");
        assertEquals(512, body.path("max_tokens").asInt(), "Request must include max_tokens");
    }

    // =========================================================================
    // Token usage parsing
    // =========================================================================

    /**
     * Verifies that token usage fields are parsed correctly from the {@code usage}
     * field of the OpenAI response.
     */
    @Test
    void parseTokenUsage_returnsCorrectValues_whenUsagePresent() throws Exception {
        String responseJson = buildSuccessResponse("gpt-4o", "{}", 42, 17, 59);
        JsonNode root = objectMapper.readTree(responseJson);

        TokenUsage usage = client.parseTokenUsage(root);

        assertEquals("gpt-4o", usage.model(), "model must match response model field");
        assertEquals(42, usage.promptTokens(), "prompt_tokens must be 42");
        assertEquals(17, usage.completionTokens(), "completion_tokens must be 17");
        assertEquals(59, usage.totalTokens(), "total_tokens must be 59");
        assertTrue(usage.isAvailable(), "usage must be marked as available");
    }

    /**
     * Verifies that {@link TokenUsage#UNAVAILABLE} is returned when the {@code usage}
     * field is absent from the response (e.g. streaming responses).
     */
    @Test
    void parseTokenUsage_returnsUnavailable_whenUsageFieldAbsent() throws Exception {
        String responseJson = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";
        JsonNode root = objectMapper.readTree(responseJson);

        TokenUsage usage = client.parseTokenUsage(root);

        assertEquals(TokenUsage.UNAVAILABLE, usage);
    }

    /**
     * Verifies that a successful call with token usage data in the response
     * results in the correct content being returned (end-to-end token usage path).
     */
    @Test
    void call_parsesTokenUsage_fromSuccessfulResponse() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(buildSuccessResponse("gpt-4o", "{\"summary\":\"test\"}", 100, 50, 150)));

        // The call should succeed and return the content — token usage is logged internally
        String result = client.call("test").block();
        assertNotNull(result, "Result must not be null");
        assertTrue(result.contains("summary"), "Result must contain the content");
    }

    // =========================================================================
    // HTTP error mapping
    // =========================================================================

    /**
     * Verifies that HTTP 401 is mapped to {@link LlmAuthException}.
     */
    @Test
    void call_throwsLlmAuthException_on401() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"Invalid API key\"}}"));

        var mono = client.call("test");
        assertThrows(LlmAuthException.class, mono::block);
    }

    /**
     * Verifies that HTTP 429 is mapped to {@link LlmRateLimitException}.
     */
    @Test
    void call_throwsLlmRateLimitException_on429() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":{\"message\":\"Rate limit exceeded\"}}"));

        var mono = client.call("test");
        assertThrows(LlmRateLimitException.class, mono::block);
    }

    /**
     * Verifies that HTTP 500 is mapped to {@link LlmCommunicationException}.
     */
    @Test
    void call_throwsLlmCommunicationException_on500() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":{\"message\":\"Internal server error\"}}"));

        var mono = client.call("test");
        assertThrows(LlmCommunicationException.class, mono::block);
    }

    // =========================================================================
    // Malformed response parsing
    // =========================================================================

    /**
     * Verifies that various malformed OpenAI responses all throw
     * {@link LlmCommunicationException}: empty choices array, missing message field,
     * null content field, and completely non-JSON body.
     */
    @ParameterizedTest(name = "throws LlmCommunicationException for malformed response: {0}")
    @MethodSource("malformedResponseBodies")
    void call_throwsLlmCommunicationException_forMalformedResponse(String description, String responseBody) {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(responseBody));

        var mono = client.call("test");
        assertThrows(LlmCommunicationException.class, mono::block,
                "Expected LlmCommunicationException for: " + description);
    }

    static Stream<Arguments> malformedResponseBodies() {
        return Stream.of(
            Arguments.of("empty choices array",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":0,\"total_tokens\":5}}"),
            Arguments.of("missing message field",
                "{\"choices\":[{\"finish_reason\":\"stop\"}]}"),
            Arguments.of("null content field",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null}}]}"),
            Arguments.of("non-JSON response body",
                "this is not json at all")
        );
    }

    // =========================================================================
    // Refusal handling
    // =========================================================================

    /**
     * Verifies that when the model sets {@code choices[0].message.refusal} instead of
     * {@code content}, a {@link LlmRefusalException} is thrown with the refusal reason.
     */
    @Test
    void call_throwsLlmRefusalException_whenModelRefuses() {
        String refusalResponse = """
                {
                  "id": "chatcmpl-test",
                  "model": "gpt-4o",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "refusal": "I cannot assist with that request."
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
                }
                """;

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(refusalResponse));

        var mono = client.call("test");
        LlmRefusalException ex = assertThrows(LlmRefusalException.class, mono::block);

        assertEquals("I cannot assist with that request.", ex.getRefusalReason(),
                "Refusal reason must match the value from the response");
        assertTrue(ex.getMessage().contains("I cannot assist with that request."),
                "Exception message must include the refusal reason");
    }

    /**
     * Verifies that a response with a non-null {@code content} field is returned normally
     * even when a {@code refusal} field is absent — i.e. refusal detection does not
     * interfere with normal responses.
     */
    @Test
    void call_returnsContent_whenNoRefusalFieldPresent() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(buildSuccessResponse("gpt-4o", "{\"summary\":\"OK\"}", 5, 5, 10)));

        String result = client.call("test").block();

        assertNotNull(result);
        assertEquals("{\"summary\":\"OK\"}", result);
    }

    // =========================================================================
    // Timeout
    // =========================================================================

    /**
     * Verifies that when the server does not respond within the configured timeout,
     * a {@link LlmTimeoutException} is thrown.
     */
    @Test
    void call_throwsLlmTimeoutException_whenServerDoesNotRespond() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBodyDelay(5, TimeUnit.SECONDS)
                .setBody(buildSuccessResponse("gpt-4o", "{}", 1, 1, 2)));

        var mono = client.call("test");
        assertThrows(LlmTimeoutException.class, mono::block);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a well-formed OpenAI Chat Completions response JSON string.
     */
    private static String buildSuccessResponse(String model, String content,
                                                int promptTokens, int completionTokens,
                                                int totalTokens) {
        // Escape the content string for embedding in JSON
        String escapedContent = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return String.format("""
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "model": "%s",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "%s"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": %d,
                    "completion_tokens": %d,
                    "total_tokens": %d
                  }
                }
                """, model, escapedContent, promptTokens, completionTokens, totalTokens);
    }
}
