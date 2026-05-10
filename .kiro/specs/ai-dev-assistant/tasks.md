# Implementation Plan: AI-Powered Developer Assistant

## Overview

Implement a production-grade Spring Boot 4.0.6 / Java 25 REST API that accepts code snippets, constructs structured LLM prompts, delegates to OpenAI, validates the structured JSON response, and returns deterministic, schema-compliant insights. The implementation follows a bottom-up approach: project scaffolding → OpenAPI contract → core components → integration wiring → observability → tests.

## Tasks

- [x] 1. Scaffold project structure and build configuration
  - Create the Maven `pom.xml` with Java 25 compiler settings, Spring Boot 4.0.6 parent, and all required dependencies: `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-webflux`, `jackson-databind`, `lombok`, `spring-boot-starter-test`, and `jqwik` for property-based testing.
  - Add the `openapi-generator-maven-plugin` configured to read `openapi.yaml` from the project root and generate sources into `target/generated-sources/openapi`.
  - Create the main application class `AiDevAssistantApplication.java` with `@SpringBootApplication`.
  - Create the package skeleton: `controller`, `service`, `llm`, `prompt`, `dto`, `validator`, `config`, `exception`, `util`.
  - _Requirements: 1.6, 2.4, 2.5_

- [x] 2. Define the OpenAPI contract
  - [x] 2.1 Create `openapi.yaml` at the project root
    - Define `POST /api/v1/code/analyze` with request body referencing `AnalysisRequest` and response referencing `AnalysisResponse`.
    - Define `AnalysisRequest` schema with `code` (string, required, minLength: 1) and `language` (string, required, minLength: 1).
    - Define `AnalysisResponse` schema with `summary` (string, required), `issues` (array of strings, required), and `improvements` (array of strings, required). Do NOT include a `confidence` field — it is deferred to a future version.
    - Define `ErrorResponse` schema with `error` (string, required), `message` (string, required), and `requestId` (string, required).
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 2.2 Verify code generation from `openapi.yaml`
    - Run `mvn generate-sources` and confirm `AnalysisRequest`, `AnalysisResponse`, `ErrorResponse`, and the `CodeApi` interface are generated under `target/generated-sources/openapi`.
    - _Requirements: 2.4, 2.5_

- [x] 3. Implement application configuration
  - [x] 3.1 Create `LlmProperties` configuration class
    - Annotate with `@ConfigurationProperties(prefix = "llm.openai")` and `@Validated`.
    - Declare fields: `apiKey` (`@NotBlank`), `model` (default `"gpt-4o"`), `maxTokens` (default `1024`), `timeoutSeconds` (default `30`), `serviceTimeoutSeconds` (default `70`).
    - Register with `@EnableConfigurationProperties` on the main application class or a `@Configuration` class.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 11.3_

  - [x] 3.2 Create `application.properties` / `application.yml`
    - Add placeholder `llm.openai.api-key` (reads from environment variable `LLM_OPENAI_API_KEY`).
    - Document default values for `model`, `max-tokens`, `timeout-seconds`, and `service-timeout-seconds` with comments.
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 11.3_

- [x] 4. Implement the custom exception hierarchy
  - Create `LlmTimeoutException`, `LlmValidationException`, `LlmCommunicationException`, and `LlmResponseFailureException` as unchecked exceptions in the `exception` package.
  - Each exception must accept a descriptive `message` string in its constructor.
  - `LlmValidationException` must also accept an optional list of missing field names.
  - _Requirements: 6.2, 6.3, 6.4, 6.5, 7.3_

- [x] 5. Implement `PromptBuilder`
  - [x] 5.1 Create `PromptBuilder` class in the `prompt` package
    - Implement `build(AnalysisRequest request): String` using a string template that injects `request.getLanguage()` and `request.getCode()` verbatim.
    - The prompt must include: role definition, task description, the required JSON output schema (`summary`, `issues`, `improvements`), and an explicit constraint prohibiting any text outside the JSON block.
    - Wrap the injected `code` value between `--- BEGIN CODE ---` and `--- END CODE ---` delimiters to reduce prompt injection risk (Requirement 12.1).
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 12.1_

  - [x]* 5.2 Write property test: prompt is non-blank for all valid inputs
    - **Property 1: Prompt non-blank for all valid inputs**
    - Use jqwik `@Property` with arbitrary non-blank strings for `code` and `language`.
    - Assert `PromptBuilder.build(request)` is never blank.
    - **Validates: Requirement 3.5**

  - [x]* 5.3 Write property test: prompt contains injected language
    - **Property 2: Prompt contains injected language**
    - Use jqwik `@Property` with arbitrary non-blank `language` values.
    - Assert the returned prompt contains the exact `language` string.
    - **Validates: Requirement 3.2**

  - [x]* 5.4 Write property test: prompt contains injected code
    - **Property 3: Prompt contains injected code**
    - Use jqwik `@Property` with arbitrary non-blank `code` values.
    - Assert the returned prompt contains the exact `code` string.
    - **Validates: Requirement 3.3**

  - [x]* 5.5 Write unit tests for `PromptBuilder`
    - Verify the prompt contains the JSON schema definition (the `summary`, `issues`, `improvements` field names).
    - Verify the prompt contains a constraint against text outside the JSON block.
    - _Requirements: 10.1_

- [x] 6. Checkpoint — Ensure all PromptBuilder tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement `LlmResponseValidator`
  - [x] 7.1 Create `LlmResponseValidator` class in the `validator` package
    - Implement `validate(String rawResponse): AnalysisResponse`.
    - Step 1: Throw `LlmValidationException("Empty LLM response")` if input is blank.
    - Step 2: Strip markdown code fences (` ```json ... ``` ` or ` ``` ... ``` `).
    - Step 3: Extract the outermost JSON object using first `{` / last `}` scan (known limitation FL-3: breaks on multiple top-level objects).
    - Step 4: Parse with Jackson `ObjectMapper`.
    - Step 5: Verify `summary` is a non-null string; `issues` is a non-null array of strings; `improvements` is a non-null array of strings. Throw `LlmValidationException` listing missing fields if any check fails.
    - Step 6: Map to `AnalysisResponse` and return.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x]* 7.2 Write property test: validator round-trip for valid JSON
    - **Property 4: Validator round-trip for valid JSON**
    - Use jqwik `@Property` to generate arbitrary valid JSON strings with non-null `summary`, `issues` array, and `improvements` array.
    - Assert `validate(json)` returns an `AnalysisResponse` whose fields equal the generated values.
    - **Validates: Requirement 5.6**

  - [x]* 7.3 Write property test: validator rejects non-conforming JSON
    - **Property 5: Validator rejects all non-conforming JSON**
    - Use jqwik `@Property` to generate JSON objects missing at least one required field.
    - Assert `validate(json)` always throws `LlmValidationException`.
    - **Validates: Requirement 5.4**

  - [x]* 7.4 Write property test: validator rejects blank/empty input
    - **Property 6: Validator rejects blank/empty input**
    - Use jqwik `@Property` with blank strings (empty, whitespace-only).
    - Assert `validate(input)` always throws `LlmValidationException`.
    - **Validates: Requirement 5.5**

  - [x]* 7.5 Write unit tests for `LlmResponseValidator`
    - Test acceptance of valid JSON conforming to the output schema.
    - Test rejection of JSON missing `summary`, missing `issues`, missing `improvements`.
    - Test rejection of blank and empty input.
    - Test extraction from a response wrapped in markdown code fences.
    - _Requirements: 10.2_

- [x] 8. Checkpoint — Ensure all Validator tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement `LlmClient` interface and `OpenAiClient`
  - [x] 9.1 Create `LlmClient` interface in the `llm` package
    - Declare `Mono<String> call(String prompt)` (reactive — returns a cold Mono).
    - _Requirements: 4.1_

  - [x] 9.2 Create `OpenAiClient` implementing `LlmClient`
    - Inject `LlmProperties`, a `WebClient` bean, `ObjectMapper`, `AnalysisResponseSchemaFactory`, and `LlmMetrics`.
    - Build the OpenAI Chat Completions request body with `model`, `temperature: 0.2`, `max_tokens`, and `response_format.type = "json_schema"` (via `AnalysisResponseSchemaFactory`).
    - Apply a `responseTimeout` from `LlmProperties.timeoutSeconds` via `Mono.timeout()`.
    - On timeout, emit `LlmTimeoutException`. On HTTP 401, emit `LlmAuthException`. On HTTP 429, emit `LlmRateLimitException`. On other errors, emit `LlmCommunicationException`.
    - Detect `choices[0].message.refusal` and emit `LlmRefusalException`.
    - Parse `TokenUsage` from response `usage` field and record to `LlmMetrics`.
    - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9_

  - [x] 9.3 Create `WebClientConfig` in the `config` package
    - Declare a `@Bean WebClient openAiWebClient(LlmProperties props)` with:
      - Bounded `ConnectionProvider` (max 50 connections, 5s acquire timeout, 30s idle, 60s lifetime)
      - 2 MB codec buffer limit (prevents `DataBufferLimitException`)
      - 5-second TCP connect timeout via `ChannelOption.CONNECT_TIMEOUT_MILLIS`
    - _Requirements: 4.2, 9.1, 14.1, 14.2, 14.3_

  - [x] 9.4 Create `AnalysisResponseSchemaFactory` in the `llm` package
    - Build the `response_format.json_schema` JSON once in the constructor and cache as a string.
    - `addResponseFormat(ObjectNode root)` parses the cached string — avoids rebuilding ~20 nodes per request.
    - _Requirements: 4.5, 14.5_

- [x] 10. Implement `CodeAnalysisService`
  - Create `CodeAnalysisService` in the `service` package.
  - Inject `PromptBuilder`, `LlmClient`, `LlmResponseValidator`, `LlmProperties`, and `LlmMetrics`.
  - Implement `analyze(AnalysisRequest request): AnalysisResponse` using a reactive pipeline:
    1. Capture `requestId` from MDC.
    2. Call `promptBuilder.build(request)` — throws `PromptTooLargeException` if too long.
    3. Start latency timer via `llmMetrics.startLatencyTimer()`.
    4. Build reactive pipeline: `llmClient.call(prompt)` → `validator.validate(raw)` on `Schedulers.boundedElastic()`.
    5. On first `LlmValidationException`, increment retry counter and retry once.
    6. On second `LlmValidationException`, throw `LlmResponseFailureException`.
    7. Apply `Mono.timeout(serviceDeadline)` — cancels HTTP request on expiry.
    8. Record outcome metrics (latency, request count, error type) on success or failure.
  - _Requirements: 6.1, 6.2, 11.1, 11.2, 13.2, 14.4_

- [x] 11. Implement `CodeAnalysisController`
  - Create `CodeAnalysisController` in the `controller` package implementing the generated `CodeApi` interface.
  - Inject `CodeAnalysisService`.
  - Implement `analyzeCode(AnalysisRequest request)` by delegating to `service.analyze(request)` and returning `ResponseEntity.ok(result)`.
  - _Requirements: 1.1, 1.2, 1.3_

- [x] 12. Implement `GlobalExceptionHandler`
  - Create `GlobalExceptionHandler` annotated with `@RestControllerAdvice` in the `exception` package.
  - Map `PromptTooLargeException` → HTTP 400.
  - Map `LlmRefusalException` → HTTP 422.
  - Map `LlmRateLimitException` → HTTP 429.
  - Map `LlmTimeoutException` → HTTP 504.
  - Map `LlmValidationException` and `LlmResponseFailureException` → HTTP 502.
  - Map `LlmAuthException` and `LlmCommunicationException` → HTTP 500.
  - Map Spring `MethodArgumentNotValidException` (Bean Validation failures) → HTTP 400.
  - Map all other `Exception` → HTTP 500.
  - Each handler method must build an `ErrorResponse` with `error` (exception class name), `message` (exception message), and `requestId` (read from MDC key `requestId`).
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 1.4, 1.5, 6.3, 6.4, 6.5_

- [x] 13. Implement request ID filter and MDC propagation
  - [x] 13.1 Create `RequestIdFilter` in the `util` package
    - Implement `OncePerRequestFilter` with `@Order(Ordered.HIGHEST_PRECEDENCE)`.
    - Read `X-Request-Id` header; if absent, generate `UUID.randomUUID().toString()`.
    - Store value in `MDC.put("requestId", requestId)`.
    - Add `X-Request-Id` to the response headers.
    - Clear MDC in a `finally` block after the filter chain completes.
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 13.2 Create `MdcTaskDecorator` in the `util` package
    - Implement Spring's `TaskDecorator` interface.
    - Capture the current MDC map before the task runs and restore it inside the async thread; clear after completion.
    - Register as the decorator on the application's `AsyncTaskExecutor` if async processing is used.
    - _Requirements: 8.6_

  - [x] 13.3 Configure logging pattern in `application.properties`
    - Set `logging.pattern.console` to `[%thread] [%X{requestId:-unknown}] [${PID:-}] %level::%logger{0} %msg%n`.
    - _Requirements: 8.4_

- [x] 14. Add structured logging to `CodeAnalysisService`
  - After building the prompt, log the sanitized prompt at DEBUG level (replace the `code` value with `[REDACTED]`).
  - After receiving the raw LLM response, log it at DEBUG level.
  - After successful validation, log the parsed `AnalysisResponse` at DEBUG level.
  - On retry, log a WARN with the attempt number and the validation error.
  - On exception propagation, log at ERROR level with the `requestId`.
  - _Requirements: 8.5_

- [x] 15. Checkpoint — Wire all components and verify application starts
  - Ensure the application starts without errors using `mvn spring-boot:run` (or equivalent).
  - Confirm that a missing `llm.openai.api-key` property causes startup failure with a descriptive error.
  - Ensure all tests pass, ask the user if questions arise.

- [x] 16. Write integration and golden tests
  - [x] 16.1 Write integration test: HTTP 200 with valid mock LLM response
    - Use `@SpringBootTest` + `MockMvc` with a mock `LlmClient` bean returning a valid JSON string.
    - POST to `/api/v1/code/analyze` with a valid `AnalysisRequest`.
    - Assert HTTP 200 and that the response body is a valid `AnalysisResponse`.
    - _Requirements: 10.3_

  - [x] 16.2 Write golden test: fixed input → exact output
    - Provide a fixed `AnalysisRequest` and a fixed mock LLM JSON response.
    - Assert the exact values of `summary`, `issues`, and `improvements` in the response body (no `confidence` field).
    - _Requirements: 10.4_

  - [x] 16.3 Write integration test: HTTP 502 after retry exhaustion
    - Configure the mock `LlmClient` to always return invalid JSON.
    - POST to `/api/v1/code/analyze`.
    - Assert HTTP 502 and that the error response body contains `error`, `message`, and `requestId` fields.
    - _Requirements: 10.5, 6.4_

  - [x]* 16.4 Write integration test: HTTP 400 on blank `code` field
    - POST with `{ "code": "", "language": "Java" }`.
    - Assert HTTP 400 with a structured error body.
    - _Requirements: 1.4_

  - [x]* 16.5 Write integration test: HTTP 504 on LLM timeout
    - Configure the mock `LlmClient` to throw `LlmTimeoutException`.
    - Assert HTTP 504 with a structured error body containing `requestId`.
    - _Requirements: 6.3, 7.3_

  - [x]* 16.6 Write integration test: `X-Request-Id` header propagation
    - Send a request with a custom `X-Request-Id` header value.
    - Assert the same value appears in the response `X-Request-Id` header and in the error body `requestId` field (on error paths).
    - _Requirements: 8.2, 8.3_

- [x] 17. Final checkpoint — Ensure all tests pass
  - Run `mvn verify` and confirm all unit, property-based, and integration tests pass.
  - Ensure all tests pass, ask the user if questions arise.

- [x] 18. Add Micrometer metrics and observability
  - Create `LlmMetrics` component in the `config` package with pre-registered meters for all metric types.
  - Add `spring-boot-starter-actuator` and `micrometer-registry-prometheus` dependencies.
  - Wire `LlmMetrics` into `CodeAnalysisService` (request counter, latency timer, retry counter, error counter).
  - Wire `LlmMetrics` into `OpenAiClient` (token usage distribution summaries).
  - Configure `application.properties`: expose `health,info,prometheus,metrics` endpoints, p50/p95/p99 histogram for latency.
  - Write `LlmMetricsTest` using `SimpleMeterRegistry` to verify all meters register and increment correctly.
  - _Requirements: 13.1, 13.2, 13.3, 13.4_

- [x] 19. Performance hardening
  - Update `WebClientConfig` with bounded `ConnectionProvider` (max 50, 5s acquire, 30s idle, 60s lifetime), 2 MB codec buffer, 5s TCP connect timeout.
  - Update `AnalysisResponseSchemaFactory` to cache the serialized `response_format` JSON string in the constructor.
  - Update `CodeAnalysisService` to offload `validator.validate()` to `Schedulers.boundedElastic()`.
  - Update `LlmMetrics` to pre-register all meters in the constructor.
  - Add server tuning to `application.properties`: compression, Tomcat thread pool, connection/keep-alive timeouts.
  - Add Jackson tuning: `fail-on-unknown-properties=false`, `default-property-inclusion=non_null`.
  - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7_

- [x] 20. Containerisation
  - Create multi-stage `Dockerfile` (Maven builder + JRE-only runtime, non-root user uid 1001, `HEALTHCHECK` via `/actuator/health`).
  - Create `.dockerignore` with whitelist approach (excludes secrets, `.env`, `.kiro/`, `.git/`).
  - Add `jib-maven-plugin` 3.5.1 to `pom.xml` (distroless Java 21 base, non-root uid 65532, container-aware JVM flags).
  - _Requirements: 15.1, 15.2, 15.3, 15.4_

- [x] 21. OpenAPI contract enhancements
  - Add all missing response codes (422, 429) with descriptions to `openapi.yaml`.
  - Add `X-Request-Id` header documentation on request and all responses.
  - Add `maxLength` constraints on `AnalysisRequest` fields.
  - Add `info.contact`, `info.license`, and request body examples.
  - Update `CodeAnalysisController` to accept the generated `xRequestId` parameter.
  - _Requirements: 1.7, 1.8, 2.2_

- [x] 22. Configuration hardening
  - Add `maxPromptChars` field to `LlmProperties` with `@Min(100)` validation.
  - Add `@PostConstruct validate()` to enforce `serviceTimeoutSeconds > timeoutSeconds * 2` at startup.
  - Add `.gitignore` (excludes `target/`, `.jqwik-database`, `.env`, `.kiro/settings/`).
  - _Requirements: 3.6, 9.6, 9.7, 9.8_

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP.
- Each task references specific requirements for traceability.
- Checkpoints ensure incremental validation at key integration points.
- Property tests (jqwik) validate universal correctness properties defined in the design document.
- Unit tests validate specific examples and edge cases.
- DTOs and the Controller interface are generated from `openapi.yaml` — do not hand-author duplicates.
