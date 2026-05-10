package com.aidevassistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Spring configuration that produces the {@link WebClient} bean used by
 * {@code OpenAiClient} to communicate with the OpenAI Chat Completions API.
 *
 * <p>The bean is named {@code openAiWebClient} so that {@code OpenAiClient}
 * can inject it unambiguously via {@code @Qualifier("openAiWebClient")}.
 *
 * <h3>Connection pool</h3>
 * <p>Uses a named {@link ConnectionProvider} with explicit bounds to prevent
 * unbounded connection growth under load:
 * <ul>
 *   <li>Max connections: 50 — sufficient for typical LLM API concurrency</li>
 *   <li>Pending acquire timeout: 5s — fail fast rather than queue indefinitely</li>
 *   <li>Max idle time: 30s — release idle connections before the server closes them</li>
 *   <li>Max life time: 60s — rotate connections to avoid stale TCP state</li>
 * </ul>
 *
 * <h3>Codec buffer limit</h3>
 * <p>The default Reactor Netty codec buffer is 256 KB. OpenAI responses for large
 * code analysis can exceed this. The limit is raised to 2 MB to prevent
 * {@code DataBufferLimitException} on large responses.
 *
 * <h3>TCP connect timeout</h3>
 * <p>A 5-second TCP connect timeout is set independently of the HTTP response timeout.
 * This prevents the client from hanging indefinitely if the OpenAI endpoint is
 * unreachable at the network level.
 */
@Configuration
public class WebClientConfig {

    /** Maximum in-memory buffer size for response bodies (2 MB). */
    private static final int MAX_IN_MEMORY_SIZE_BYTES = 2 * 1024 * 1024;

    /** Maximum number of connections in the pool. */
    private static final int MAX_CONNECTIONS = 50;

    /** TCP-level connect timeout — distinct from the HTTP response timeout. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /**
     * Registers a shared {@link ObjectMapper} bean used by {@code PromptBuilder},
     * {@code LlmResponseValidator}, {@code OpenAiClient}, and {@code AnalysisResponseSchemaFactory}.
     *
     * <p>Spring Boot auto-configures an {@code ObjectMapper} when the full web context is loaded,
     * but declaring it explicitly here ensures it is available in all test contexts including
     * {@code WebEnvironment.NONE}.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Creates a {@link WebClient} pre-configured with:
     * <ul>
     *   <li>Base URL: {@code https://api.openai.com}</li>
     *   <li>Default {@code Authorization: Bearer <apiKey>} header</li>
     *   <li>Bounded connection pool with idle/life time limits</li>
     *   <li>2 MB codec buffer (prevents {@code DataBufferLimitException})</li>
     *   <li>5-second TCP connect timeout</li>
     * </ul>
     *
     * @param props the externalized LLM configuration properties
     * @return a fully configured {@link WebClient} instance
     */
    @Bean("openAiWebClient")
    public WebClient openAiWebClient(LlmProperties props) {
        // Named connection pool with explicit bounds
        ConnectionProvider connectionProvider = ConnectionProvider.builder("openai-pool")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(30))
                .build();

        // Reactor Netty HttpClient with TCP connect timeout
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS);

        // Raise the codec buffer limit to handle large LLM responses.
        // Uses ExchangeStrategies.builder().codecs() — the supported API in Spring 6+.
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(MAX_IN_MEMORY_SIZE_BYTES))
                .build();

        return WebClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(exchangeStrategies)
                .build();
    }
}
