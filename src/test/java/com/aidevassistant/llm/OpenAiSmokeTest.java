package com.aidevassistant.llm;

import com.aidevassistant.config.LlmMetrics;
import com.aidevassistant.config.LlmProperties;
import com.aidevassistant.config.WebClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test that makes a real HTTP call to the OpenAI API.
 *
 * <p><strong>This test is disabled by default and must never run in CI.</strong>
 * Enable it manually when you need to verify:
 * <ul>
 *   <li>The {@code json_schema} structured output format is accepted by the current model</li>
 *   <li>The response schema has not drifted (new fields, renamed fields)</li>
 *   <li>Token usage fields are present in the live response</li>
 *   <li>The configured model supports the {@code response_format} parameters</li>
 * </ul>
 *
 * <h3>How to run</h3>
 * <ol>
 *   <li>Set the environment variable: {@code export LLM_OPENAI_API_KEY=sk-...}</li>
 *   <li>Remove the {@code @Disabled} annotation temporarily</li>
 *   <li>Run: {@code mvn test -Dtest=OpenAiSmokeTest}</li>
 *   <li>Re-add {@code @Disabled} before committing</li>
 * </ol>
 *
 * <p>Alternatively, run with the system property:
 * {@code mvn test -Dtest=OpenAiSmokeTest -DLLM_OPENAI_API_KEY=sk-...}
 */
@Disabled("Smoke test — requires a real OpenAI API key. Run manually only.")
class OpenAiSmokeTest {

    @Test
    void call_returnsValidJsonContent_againstRealOpenAiApi() {
        String apiKey = System.getenv("LLM_OPENAI_API_KEY");
        assertNotNull(apiKey, "LLM_OPENAI_API_KEY environment variable must be set to run this test");
        assertFalse(apiKey.isBlank(), "LLM_OPENAI_API_KEY must not be blank");

        LlmProperties props = new LlmProperties();
        props.setApiKey(apiKey);
        props.setModel("gpt-4o");
        props.setMaxTokens(256);
        props.setTimeoutSeconds(30);

        WebClient webClient = new WebClientConfig().openAiWebClient(props);
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiClient client = new OpenAiClient(props, webClient, objectMapper,
                new AnalysisResponseSchemaFactory(objectMapper),
                new LlmMetrics(new SimpleMeterRegistry()));

        String prompt = """
                You are a senior backend engineer.

                Analyze the following Java code and return ONLY a strict JSON object.

                Required JSON schema:
                {
                  "summary": "<string: one-sentence summary>",
                  "issues": ["<string>", ...],
                  "improvements": ["<string>", ...]
                }

                Rules:
                - Output ONLY the JSON object. No markdown, no explanation, no text outside the JSON.

                --- BEGIN CODE ---
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("Hello World");
                    }
                }
                --- END CODE ---
                """;

        String result = client.call(prompt).block();

        // Basic structural assertions — not exact value checks since LLM output varies
        assertNotNull(result, "Response content must not be null");
        assertFalse(result.isBlank(), "Response content must not be blank");
        assertTrue(result.contains("summary"),
                "Response must contain 'summary' field. Got: " + result);
        assertTrue(result.contains("issues"),
                "Response must contain 'issues' field. Got: " + result);
        assertTrue(result.contains("improvements"),
                "Response must contain 'improvements' field. Got: " + result);

        System.out.println("=== Smoke test response ===");
        System.out.println(result);
        System.out.println("===========================");
    }
}
