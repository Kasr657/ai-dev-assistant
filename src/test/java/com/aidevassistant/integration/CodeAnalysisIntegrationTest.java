package com.aidevassistant.integration;

import com.aidevassistant.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Mono;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the {@code POST /api/v1/code/analyze} endpoint.
 *
 * <p>Uses a full Spring Boot application context with a mock {@link LlmClient} bean
 * to exercise the complete request-handling pipeline without making real LLM calls.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "llm.openai.api-key=test-key",
                "llm.openai.timeout-seconds=4",
                "llm.openai.service-timeout-seconds=10"
        }
)
@AutoConfigureMockMvc
class CodeAnalysisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    // =========================================================================
    // HTTP 200 with valid mock LLM response
    // =========================================================================

    /**
     * Verifies that when the mock LlmClient returns a valid JSON string, the endpoint
     * returns HTTP 200 and the response body contains {@code summary}, {@code issues},
     * and {@code improvements} fields.
     */
    @Test
    void analyzeCode_returns200_withValidLlmResponse() throws Exception {
        when(llmClient.call(anyString())).thenReturn(
                Mono.just("{\"summary\":\"Test summary\",\"issues\":[\"issue1\"],\"improvements\":[\"improvement1\"]}"));

        mockMvc.perform(post("/api/v1/code/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "int x = 1;",
                                  "language": "Java"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").exists())
                .andExpect(jsonPath("$.issues").isArray())
                .andExpect(jsonPath("$.improvements").isArray());
    }

    // =========================================================================
    // HTTP 502 after retry exhaustion
    // =========================================================================

    /**
     * Verifies that when the mock LlmClient always returns invalid JSON, the endpoint
     * returns HTTP 502 after exhausting both retry attempts, and the error response body
     * contains {@code error}, {@code message}, and {@code requestId} fields.
     *
     * <p>The service calls LlmClient twice (initial attempt + one retry) before throwing
     * {@link com.aidevassistant.exception.LlmResponseFailureException}, which the
     * {@link com.aidevassistant.exception.GlobalExceptionHandler} maps to HTTP 502.
     */
    @Test
    void analyzeCode_returns502_whenLlmAlwaysReturnsInvalidJson() throws Exception {
        when(llmClient.call(anyString())).thenReturn(Mono.just("this is not valid json"));

        mockMvc.perform(post("/api/v1/code/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "System.out.println(\\"hello\\");",
                                  "language": "Java"
                                }
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.requestId").exists());
    }

    // =========================================================================
    // HTTP 400 on blank code field
    // =========================================================================

    /**
     * Verifies that submitting a blank {@code code} field returns HTTP 400 with a
     * structured error body.
     */
    @Test
    void analyzeCode_returns400_whenCodeIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/code/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"language\":\"Java\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.requestId").exists());
    }

    // =========================================================================
    // HTTP 504 on LLM timeout
    // =========================================================================

    /**
     * Verifies that when the mock LlmClient emits a {@link com.aidevassistant.exception.LlmTimeoutException},
     * the endpoint returns HTTP 504 with a structured error body containing {@code requestId}.
     */
    @Test
    void analyzeCode_returns504_whenLlmTimesOut() throws Exception {
        when(llmClient.call(anyString()))
                .thenReturn(Mono.error(new com.aidevassistant.exception.LlmTimeoutException("HTTP timeout")));

        mockMvc.perform(post("/api/v1/code/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"int x = 1;\",\"language\":\"Java\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.requestId").exists());
    }

    // =========================================================================
    // X-Request-Id header propagation
    // =========================================================================

    /**
     * Verifies that when a request is sent with a custom {@code X-Request-Id} header,
     * the same value is echoed back in the response {@code X-Request-Id} header.
     *
     * <p>This validates that {@code RequestIdFilter} correctly reads the inbound header
     * and sets it on the response, and that the MDC correlation path is wired correctly.
     */
    @Test
    void analyzeCode_echoesRequestIdHeader_whenProvidedByClient() throws Exception {
        String customRequestId = "my-trace-id-abc123";
        when(llmClient.call(anyString())).thenReturn(
                Mono.just("{\"summary\":\"OK\",\"issues\":[],\"improvements\":[]}"));

        MvcResult result = mockMvc.perform(post("/api/v1/code/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", customRequestId)
                        .content("{\"code\":\"int x = 1;\",\"language\":\"Java\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", customRequestId))
                .andReturn();

        assertEquals(customRequestId, result.getResponse().getHeader("X-Request-Id"),
                "Response X-Request-Id must match the value sent in the request");
    }

    /**
     * Verifies that when no {@code X-Request-Id} header is sent, the response still
     * contains a generated {@code X-Request-Id} header (a UUID assigned by the filter).
     */
    @Test
    void analyzeCode_generatesRequestIdHeader_whenNotProvidedByClient() throws Exception {
        when(llmClient.call(anyString())).thenReturn(
                Mono.just("{\"summary\":\"OK\",\"issues\":[],\"improvements\":[]}"));

        mockMvc.perform(post("/api/v1/code/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"int x = 1;\",\"language\":\"Java\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", not(emptyOrNullString())));
    }

    /**
     * Verifies that on an error response, the {@code requestId} field in the JSON body
     * matches the {@code X-Request-Id} header sent in the request.
     */
    @Test
    void analyzeCode_errorBody_containsRequestIdMatchingHeader() throws Exception {
        String customRequestId = "error-trace-xyz";
        when(llmClient.call(anyString())).thenReturn(Mono.just("not json"));

        mockMvc.perform(post("/api/v1/code/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", customRequestId)
                        .content("{\"code\":\"int x = 1;\",\"language\":\"Java\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("X-Request-Id", customRequestId))
                .andExpect(jsonPath("$.requestId").value(customRequestId));
    }
}
