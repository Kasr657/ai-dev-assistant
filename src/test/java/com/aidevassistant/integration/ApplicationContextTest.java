package com.aidevassistant.integration;

import com.aidevassistant.AiDevAssistantApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Spring Boot application context startup behavior.
 *
 * <p>Confirms that the context loads successfully when a valid API key is provided,
 * and fails to start with a descriptive error when the API key is missing or blank.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.NONE,
        properties = "llm.openai.api-key=test-key"
)
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    /**
     * Verifies that the application context loads successfully when
     * {@code llm.openai.api-key} is set to a non-blank value.
     */
    @Test
    void contextLoads_withValidApiKey() {
        // The @SpringBootTest annotation starts the context before this method runs.
        // If startup fails, the test errors before reaching this assertion.
        assertNotNull(context, "Application context must load successfully with a valid API key");
    }
    /**
     * Verifies that the application context fails to start when
     * {@code llm.openai.api-key} is blank, and that the failure message
     * identifies the missing property.
     */
    @Test
    void contextFailsToStart_whenApiKeyIsMissing() {
        // Attempt to start the context with a blank API key.
        // Spring Boot's @ConfigurationProperties + @NotBlank should cause startup failure.
        Exception ex = assertThrows(Exception.class, () -> {
            SpringApplication app = new SpringApplication(AiDevAssistantApplication.class);
            app.setDefaultProperties(Map.of(
                    "llm.openai.api-key", "",
                    "spring.main.web-application-type", "none"
            ));
            app.run();
        });

        // The exception chain should contain a message about the missing/blank api-key
        String fullMessage = getFullExceptionMessage(ex);
        assertTrue(
                fullMessage.contains("api-key") || fullMessage.contains("apiKey"),
                "Startup failure message should identify the missing api-key property. Got: " + fullMessage
        );
    }

    /**
     * Collects the full exception message chain (message + all causes) into a single string.
     */
    private String getFullExceptionMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(" | ");
            }
            t = t.getCause();
        }
        return sb.toString();
    }
}
