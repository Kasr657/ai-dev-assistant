package com.aidevassistant;

import com.aidevassistant.config.LlmProperties;
import com.aidevassistant.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the AI Developer Assistant backend application.
 *
 * <p>This is a Spring Boot application that exposes a REST API for AI-powered
 * code analysis. It accepts code snippets, constructs structured prompts,
 * delegates to an LLM provider (OpenAI), validates the response, and returns
 * deterministic, schema-compliant insights.
 *
 * <p>Configuration is loaded from {@code application.properties} and can be
 * overridden via environment variables. The {@code LLM_OPENAI_API_KEY}
 * environment variable must be set before starting the application.
 */
@SpringBootApplication
@EnableConfigurationProperties({LlmProperties.class, RateLimitProperties.class})
public class AiDevAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiDevAssistantApplication.class, args);
    }
}
