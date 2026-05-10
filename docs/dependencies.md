# Dependencies

All versions are managed in `pom.xml`. Spring Boot dependencies are version-managed by the `spring-boot-starter-parent` BOM (4.0.6) unless a specific version is listed.

---

## Core Runtime

### `spring-boot-starter-web`
**Why:** Provides the Tomcat servlet container and Spring MVC for handling HTTP requests. Used for the `POST /api/v1/code/analyze` endpoint.

### `spring-boot-starter-webflux`
**Why:** Provides Spring WebFlux and the Reactor Netty HTTP client (`WebClient`). Used **only** for the outbound OpenAI API call — not for the inbound HTTP layer. WebFlux gives us `Mono.timeout()` which cancels the underlying HTTP connection when a deadline fires, preventing connection leaks.

### `spring-boot-starter-validation`
**Why:** Jakarta Bean Validation (`@NotNull`, `@Size`, `@Min`). Used in two places:
- Request body validation (generated from `openapi.yaml` constraints)
- `LlmProperties` startup validation (`@NotBlank`, `@Min`, `@PostConstruct`)
- `LlmResponseValidator` — validates the deserialized `AnalysisResponse` DTO

### `spring-boot-starter-actuator`
**Why:** Exposes `/actuator/health`, `/actuator/info`, and `/actuator/prometheus` endpoints. Required for the Docker `HEALTHCHECK` and for Prometheus scraping.

### `micrometer-registry-prometheus`
**Why:** Bridges Micrometer (the metrics facade) to Prometheus format. Enables the `/actuator/prometheus` scrape endpoint. All `llm.*` metrics are published here.

### `jackson-databind`
**Why:** JSON serialization/deserialization. Used for:
- Deserializing inbound `AnalysisRequest`
- Serializing outbound `AnalysisResponse`
- Building the OpenAI request body (`ObjectNode`)
- Parsing the OpenAI response (`JsonNode`)
- Introspecting `AnalysisResponse.class` to derive the JSON schema (`BeanDescription`)

### `lombok`
**Why:** Reduces boilerplate. Used for `@Data` on `LlmProperties` and `RateLimitProperties` (generates getters/setters), and `@RequiredArgsConstructor` on the controller. Optional — the project compiles without it if you prefer explicit code.

---

## OpenAPI Code Generation

### `openapi-generator-maven-plugin` (7.22.0)
**Why:** Generates Java source from `openapi.yaml` at build time. Produces:
- `AnalysisRequest`, `AnalysisResponse`, `ErrorResponse` DTOs with Jakarta validation annotations
- `ApiApi` controller interface with Spring MVC annotations

This makes `openapi.yaml` the single source of truth for the API contract. No hand-authored DTOs.

### `jackson-databind-nullable` (0.2.10)
**Why:** Runtime dependency required by the openapi-generator's Spring generator for handling nullable fields. Not used directly in application code.

### `swagger-annotations` (2.2.49)
**Why:** Runtime dependency for the `@Schema`, `@Operation`, `@ApiResponse` annotations placed on the generated `ApiApi` interface. Not used directly in application code.

---

## Resilience

### `resilience4j-reactor` (2.4.0)
**Why:** Provides `CircuitBreakerOperator.of()` for wrapping reactive `Mono` chains with a circuit breaker. The reactor variant is required because the LLM client returns `Mono<String>` — the standard Resilience4j annotations don't work with reactive types.

### `resilience4j-micrometer` (2.4.0)
**Why:** Automatically publishes circuit breaker state and call statistics to Micrometer via `TaggedCircuitBreakerMetrics`. Enables monitoring circuit breaker health in Prometheus without any manual metric recording.

### `bucket4j-core` (8.14.0)
**Why:** Token bucket rate limiting. Provides `Bucket` and `Bandwidth` for the global rate limiter in `RateLimitFilter`. The core module is used (no distributed backend) — suitable for single-instance deployments. For multi-instance, replace with `bucket4j-redis` or `bucket4j-hazelcast`.

---

## Testing

### `spring-boot-starter-test`
**Why:** Bundles JUnit 5, Mockito, AssertJ, and Spring Test support. Used for all unit and integration tests.

### `spring-boot-starter-webmvc-test`
**Why:** Provides `@AutoConfigureMockMvc` for Spring Boot 4. Required separately from `spring-boot-starter-test` in Spring Boot 4 — the annotation moved to a dedicated module.

### `jqwik` (1.9.3)
**Why:** Property-based testing framework. Used to verify universal correctness properties (e.g. "for all non-blank inputs, the prompt is non-blank") with 50 randomly generated test cases each. Catches edge cases that example-based tests miss.

### `mockwebserver` / OkHttp (5.3.2)
**Why:** Spins up a real local HTTP server for testing `OpenAiClient`. This is the correct way to test WebClient-based code — mocking WebClient internals is fragile and doesn't test the actual HTTP behaviour. MockWebServer lets us verify the exact request body sent to OpenAI and simulate all error responses (401, 429, 500, timeouts).

---

## Version Management

All Spring Boot ecosystem dependencies (web, webflux, validation, actuator, jackson, lombok, etc.) are version-managed by the Spring Boot BOM. You should not specify versions for these — the BOM ensures compatibility.

Dependencies that require explicit versions (not in the Spring Boot BOM):

| Dependency | Version | Reason for explicit version |
|---|---|---|
| `openapi-generator-maven-plugin` | 7.22.0 | Not in Spring Boot BOM |
| `jackson-databind-nullable` | 0.2.10 | OpenAPI generator runtime, not in BOM |
| `swagger-annotations` | 2.2.49 | OpenAPI generator runtime, not in BOM |
| `resilience4j-reactor` | 2.4.0 | Not in Spring Boot BOM |
| `resilience4j-micrometer` | 2.4.0 | Not in Spring Boot BOM |
| `bucket4j-core` | 8.14.0 | Not in Spring Boot BOM |
| `jqwik` | 1.9.3 | Not in Spring Boot BOM |
| `mockwebserver` (OkHttp) | 5.3.2 | Not in Spring Boot BOM |
| `jib-maven-plugin` | 3.5.1 | Build plugin, not in BOM |
