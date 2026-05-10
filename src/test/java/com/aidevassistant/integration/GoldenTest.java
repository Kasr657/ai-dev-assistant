package com.aidevassistant.integration;

import com.aidevassistant.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Golden test for the {@code POST /api/v1/code/analyze} endpoint.
 *
 * <p>Provides a fixed request and a fixed mock LLM JSON response, then asserts
 * the exact values of {@code summary}, {@code issues}, and {@code improvements}
 * in the response body. This test acts as a regression guard — if the parsing,
 * validation, or mapping logic changes, this test will catch it.
 *
 * <p>No {@code confidence} field is expected; it is deferred to a future version.
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
class GoldenTest {

    private static final String FIXED_REQUEST_BODY =
            "{\"code\":\"public class Hello { public static void main(String[] args) { System.out.println(\\\"Hello World\\\"); } }\","
            + "\"language\":\"Java\"}";

    private static final String FIXED_LLM_RESPONSE =
            "{\"summary\":\"Simple Hello World program\","
            + "\"issues\":[\"No error handling\",\"Magic string usage\"],"
            + "\"improvements\":[\"Add error handling\",\"Extract string to constant\"]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmClient llmClient;

    /**
     * Asserts that a fixed input always produces the exact expected output fields.
     */
    @Test
    void goldenTest_fixedInputProducesExactOutput() throws Exception {
        when(llmClient.call(anyString())).thenReturn(Mono.just(FIXED_LLM_RESPONSE));

        mockMvc.perform(post("/api/v1/code/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FIXED_REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.summary").value("Simple Hello World program"))
                .andExpect(jsonPath("$.issues[0]").value("No error handling"))
                .andExpect(jsonPath("$.issues[1]").value("Magic string usage"))
                .andExpect(jsonPath("$.issues.length()").value(2))
                .andExpect(jsonPath("$.improvements[0]").value("Add error handling"))
                .andExpect(jsonPath("$.improvements[1]").value("Extract string to constant"))
                .andExpect(jsonPath("$.improvements.length()").value(2))
                .andExpect(jsonPath("$.confidence").doesNotExist());
    }
}
