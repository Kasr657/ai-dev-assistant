# Requirements Document

## Introduction

This document defines the requirements for the AI-powered developer assistant backend system. The system is a production-grade, backend-only REST API built with Java 25 and Spring Boot 4.0.6. It accepts code snippets via HTTP, constructs structured prompts, delegates analysis to an LLM provider (OpenAI initially), validates the structured JSON response, and returns deterministic, schema-compliant insights to the caller.

The system is explicitly **not** a chatbot. It is a deterministic, single-turn analysis pipeline where the LLM is treated as an untrusted external component whose output is always validated before being returned.

---

## Glossary

- **System**: The AI-powered developer assistant backend application.
- **API**: The REST API exposed by the System over HTTP.
- **Controller**: The Spring MVC layer that accepts HTTP requests and returns HTTP responses.
- **Service**: The orchestration layer that coordinates the analysis workflow.
- **PromptBuilder**: The component responsible for constructing structured LLM prompts from request inputs.
- **LlmClient**: The reactive abstraction interface for communicating with external LLM providers. Returns `Mono<String>`.
- **OpenAiClient**: The concrete implementation of LlmClient that communicates with the OpenAI API using a reactive WebClient pipeline.
- **AnalysisResponseSchemaFactory**: The component that builds and caches the OpenAI `response_format.json_schema` block.
- **Validator**: The component that extracts, parses, and validates LLM responses against the defined JSON schema.
- **LlmMetrics**: The Micrometer metrics component that records request counts, latency, retries, errors, and token usage.
- **AnalysisRequest**: The inbound DTO containing `code` (string) and `language` (string) fields.
- **AnalysisResponse**: The outbound DTO containing `summary` (string), `issues` (array of strings), and `improvements` (array of strings) fields.
- **LlmResponse**: The raw string response returned by the LlmClient.
- **RequestId**: A unique identifier assigned to each inbound HTTP request and async process for log correlation.
- **ExceptionHandler**: The centralized Spring `@RestControllerAdvice` component that handles all application exceptions.
- **OpenAPI**: The OpenAPI 3.x specification file (`openapi.yaml`) that defines all API contracts, DTOs, and validations.
- **openapi-generator-maven-plugin**: The Maven plugin (version 7.22.0) used to generate Java source code (controllers, DTOs) from the OpenAPI specification.
- **TokenUsage**: An immutable record holding `model`, `promptTokens`, `completionTokens`, and `totalTokens` parsed from the OpenAI response.

---

## Requirements

### Requirement 1: Code Analysis API Endpoint

**User Story:** As a developer, I want to submit a code snippet via a REST API, so that I can receive structured AI-generated insights about the code's quality, issues, and improvements.

#### Acceptance Criteria

1. THE System SHALL expose a `POST /api/v1/code/analyze` endpoint as defined in the `openapi.yaml` specification.
2. WHEN a request is received at `POST /api/v1/code/analyze`, THE Controller SHALL accept a JSON body conforming to the `AnalysisRequest` schema with a non-blank `code` field (minLength: 1, maxLength: 50000) and a non-blank `language` field (minLength: 1, maxLength: 100).
3. WHEN a valid `AnalysisRequest` is received, THE Controller SHALL delegate processing to the Service and return an HTTP 200 response containing a JSON body conforming to the `AnalysisResponse` schema.
4. IF the `code` field is blank or absent in the request body, THEN THE Controller SHALL return an HTTP 400 response with a structured error body describing the validation failure.
5. IF the `language` field is blank or absent in the request body, THEN THE Controller SHALL return an HTTP 400 response with a structured error body describing the validation failure.
6. THE System SHALL generate all Controller and DTO source files from the `openapi.yaml` specification using the openapi-generator-maven-plugin.
7. THE `openapi.yaml` SHALL document all response codes: 200, 400, 422, 429, 500, 502, 504, with descriptions of each cause.
8. THE `openapi.yaml` SHALL document the optional `X-Request-Id` request header and the `X-Request-Id` response header on all responses.

---

### Requirement 2: OpenAPI-Driven Contract Definition

**User Story:** As a developer, I want all API contracts, request/response schemas, and field validations to be defined in a single `openapi.yaml` file, so that the implementation is always consistent with the specification.

#### Acceptance Criteria

1. THE System SHALL maintain an `openapi.yaml` file at the project root that defines the `POST /api/v1/code/analyze` endpoint, the `AnalysisRequest` schema, and the `AnalysisResponse` schema.
2. THE `openapi.yaml` SHALL declare `code` and `language` as required fields on `AnalysisRequest` with a minimum length of 1 character.
3. THE `openapi.yaml` SHALL declare `summary`, `issues`, and `improvements` as required fields on `AnalysisResponse`. The `confidence` field SHALL NOT be included in v1 as it has no defined computation source.
4. WHEN the Maven build is executed, THE openapi-generator-maven-plugin SHALL generate Java source files for all DTOs and API interfaces defined in `openapi.yaml`.
5. THE System SHALL not contain manually authored DTO or Controller interface classes that duplicate definitions already present in `openapi.yaml`.

---

### Requirement 3: Prompt Construction

**User Story:** As a system operator, I want the system to construct deterministic, schema-enforcing prompts before calling the LLM, so that the LLM is always instructed to return strictly structured JSON output.

#### Acceptance Criteria

1. WHEN the Service initiates an analysis, THE PromptBuilder SHALL produce a prompt that includes a role definition, the analysis task description, the target programming language, the code snippet, the required JSON output schema, and explicit constraints prohibiting any text outside the JSON block.
2. THE PromptBuilder SHALL inject the `language` value from the `AnalysisRequest` into the prompt without modification.
3. THE PromptBuilder SHALL inject the `code` value from the `AnalysisRequest` into the prompt without modification.
4. THE PromptBuilder SHALL include the following required output schema in every prompt: an object with a `summary` string field, an `issues` array of strings, and an `improvements` array of strings.
5. FOR ALL valid `AnalysisRequest` inputs, THE PromptBuilder SHALL produce a non-blank prompt string (round-trip property: any valid input produces a usable prompt).
6. IF the resulting prompt exceeds the configured maximum character limit (`llm.openai.max-prompt-chars`, default 12,000), THEN THE PromptBuilder SHALL throw `PromptTooLargeException`, which the ExceptionHandler maps to HTTP 400.

---

### Requirement 4: LLM Client Abstraction

**User Story:** As a developer, I want the LLM communication to be behind an interface, so that I can swap providers (e.g., OpenAI to Claude) without changing the core business logic.

#### Acceptance Criteria

1. THE System SHALL define a `LlmClient` interface with a single method that accepts a prompt string and returns a `Mono<String>` (reactive, non-blocking).
2. THE System SHALL provide an `OpenAiClient` implementation of `LlmClient` that sends the prompt to the OpenAI Chat Completions API using a fully reactive WebClient pipeline.
3. THE `OpenAiClient` SHALL use a configurable model name, a fixed temperature of 0.2, and a configurable maximum token count, sourced from application configuration.
4. WHEN the OpenAI API does not respond within the configured timeout duration, THE `OpenAiClient` SHALL emit `LlmTimeoutException` on the reactive stream.
5. THE `OpenAiClient` SHALL send `response_format.type = "json_schema"` with the full `AnalysisResponse` schema on every request to enforce structured output at the provider level.
6. WHEN the OpenAI response contains `choices[0].message.refusal`, THE `OpenAiClient` SHALL emit `LlmRefusalException` (HTTP 422).
7. WHEN the OpenAI API returns HTTP 401, THE `OpenAiClient` SHALL emit `LlmAuthException` (HTTP 500).
8. WHEN the OpenAI API returns HTTP 429, THE `OpenAiClient` SHALL emit `LlmRateLimitException` (HTTP 429).
9. THE `OpenAiClient` SHALL parse token usage (`prompt_tokens`, `completion_tokens`, `total_tokens`) from the response and record it to Micrometer distribution summaries.
10. WHERE a Claude provider is configured in the future, THE System SHALL support a `ClaudeClient` implementation of `LlmClient` without modifying the Service or PromptBuilder.

---

### Requirement 5: Structured Output Validation

**User Story:** As a system operator, I want every LLM response to be validated against the expected JSON schema before it is returned to the caller, so that malformed or incomplete LLM output never reaches the API consumer.

#### Acceptance Criteria

1. WHEN the LlmClient returns a response, THE Validator SHALL attempt to extract a JSON object from the response string by scanning for the first `{` and last `}` characters after stripping markdown code fences.
2. THE Validator SHALL treat the extraction as failed if the extracted substring cannot be parsed as a valid JSON object by Jackson. Nested JSON objects and multiple top-level objects in the same response are out of scope for v1; the Validator SHALL use the outermost `{...}` span only.
3. WHEN a JSON object is successfully extracted, THE Validator SHALL verify that the `summary` field is present and is a string, the `issues` field is present and is an array of strings, and the `improvements` field is present and is an array of strings.
4. WHEN all required fields are present and valid, THE Validator SHALL convert the extracted JSON into an `AnalysisResponse` DTO and return it to the Service.
5. IF the extracted JSON is missing one or more required fields, THEN THE Validator SHALL throw a structured validation exception identifying the missing fields.
6. IF the LlmClient returns an empty or blank response, THEN THE Validator SHALL throw a structured validation exception indicating an empty LLM response.
7. FOR ALL valid JSON strings conforming to the output schema, THE Validator SHALL produce an equivalent `AnalysisResponse` (round-trip property: valid JSON in → valid DTO out).

---

### Requirement 6: LLM Failure Handling and Retry

**User Story:** As a system operator, I want the system to attempt recovery from transient LLM failures before surfacing an error to the caller, so that temporary LLM instability does not immediately degrade the API.

#### Acceptance Criteria

1. WHEN the Validator fails to extract valid JSON from the first LlmClient response, THE Service SHALL invoke the LlmClient exactly one additional time with the same prompt before failing.
2. IF the second LlmClient invocation also produces an invalid or unparseable response, THEN THE Service SHALL throw a structured exception indicating LLM response failure after retry.
3. IF the LlmClient throws a timeout exception, THEN THE ExceptionHandler SHALL return an HTTP 504 response with a structured error body.
4. IF the Validator throws a validation exception after all retries are exhausted, THEN THE ExceptionHandler SHALL return an HTTP 502 response with a structured error body describing the validation failure.
5. IF the LlmClient throws any unexpected exception, THEN THE ExceptionHandler SHALL return an HTTP 500 response with a structured error body.

---

### Requirement 7: Centralized Exception Handling

**User Story:** As an API consumer, I want all error responses to follow a consistent JSON structure, so that I can reliably parse and handle errors programmatically.

#### Acceptance Criteria

1. THE ExceptionHandler SHALL intercept all unhandled exceptions thrown within the request processing pipeline.
2. WHEN an exception is intercepted, THE ExceptionHandler SHALL return a JSON error body containing at minimum an `error` string field, a `message` string field, and a `requestId` string field.
3. THE ExceptionHandler SHALL map: `PromptTooLargeException` → 400, `RateLimitExceededException` → 429 (with `Retry-After: 1` header), `LlmRefusalException` → 422, `LlmRateLimitException` → 429, `LlmCircuitOpenException` → 503, `LlmTimeoutException` → 504, `LlmValidationException`/`LlmResponseFailureException` → 502, `LlmAuthException` → 500, all others → 500.
4. THE ExceptionHandler SHALL include the `RequestId` of the current request in every error response body.

---

### Requirement 8: Request Identification and Structured Logging

**User Story:** As a system operator, I want every request and async process to carry a unique request ID that appears in all log entries, so that I can filter and trace the full lifecycle of any operation in the logs.

#### Acceptance Criteria

1. WHEN an HTTP request is received, THE System SHALL generate a unique `RequestId` (UUID v4) and store it in the MDC (Mapped Diagnostic Context) under the key `requestId` for the duration of that request.
2. WHERE an `X-Request-Id` header is present in the inbound HTTP request, THE System SHALL use the provided value as the `RequestId` instead of generating a new one.
3. THE System SHALL include the `RequestId` in the HTTP response under the `X-Request-Id` header.
4. WHILE processing any request, THE System SHALL emit log entries using the pattern `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{requestId:-unknown}] [${PID:-}] %level::%logger{0} %msg%n` with timestamps in the local system timezone.
5. THE System SHALL log the sanitized prompt (with code content replaced by `[REDACTED]`), a truncated raw LlmResponse (first 500 chars), the parsed AnalysisResponse, and any exceptions at appropriate log levels (DEBUG for prompt/response, ERROR for exceptions).
6. IF an async process is spawned, THEN THE System SHALL propagate the `RequestId` from the originating request context to the async process MDC via `MdcTaskDecorator`.

---

### Requirement 9: Application Configuration

**User Story:** As a system operator, I want all LLM provider settings to be externally configurable, so that I can change models, timeouts, and token limits without modifying or redeploying application code.

#### Acceptance Criteria

1. THE System SHALL read the LLM provider API key from an externally supplied configuration property and SHALL NOT hard-code credentials in source files.
2. THE System SHALL read the LLM model name from a configurable property with a documented default value (`gpt-4o`).
3. THE System SHALL read the maximum token count from a configurable property with a documented default value (`1024`).
4. THE System SHALL read the HTTP timeout duration for LLM API calls from a configurable property with a documented default value (`30` seconds).
5. IF a required configuration property (such as the API key) is absent at startup, THEN THE System SHALL fail to start and SHALL emit a descriptive error message identifying the missing property.
6. THE System SHALL read the maximum prompt character limit from a configurable property (`llm.openai.max-prompt-chars`, default `12000`).
7. THE System SHALL enforce that `llm.openai.service-timeout-seconds` is greater than `llm.openai.timeout-seconds * 2` at startup, failing with a descriptive error if violated.
8. ALL configuration properties SHALL be overridable via environment variables using Spring Boot's relaxed binding rules (e.g., `LLM_OPENAI_API_KEY` → `llm.openai.api-key`).

---

### Requirement 10: Unit and Integration Testing

**User Story:** As a developer, I want the core components to be covered by automated tests, so that regressions are caught before deployment.

#### Acceptance Criteria

1. THE System SHALL include unit tests and property-based tests (jqwik 1.9.3, 50 tries each) for the PromptBuilder covering: non-blank output, language injection, code injection, schema field names, output constraint, code delimiters, and prompt injection resistance.
2. THE System SHALL include unit tests and property-based tests for the Validator covering: valid JSON acceptance, missing field rejection, blank/empty input rejection, markdown fence extraction, and round-trip correctness.
3. THE System SHALL include unit tests for `LlmMetrics` verifying all meters register and increment correctly using `SimpleMeterRegistry`.
4. THE System SHALL include `OpenAiClientTest` using MockWebServer (OkHttp 5.3.2) covering: success, `json_schema` enforcement, token usage parsing, HTTP 401/429/500 mapping, refusal detection, malformed responses, and timeout.
5. THE System SHALL include an integration test for the `POST /api/v1/code/analyze` endpoint covering: HTTP 200, 400, 502, 504, and `X-Request-Id` header propagation (echo and generation).
6. THE System SHALL include a golden test that provides a fixed `AnalysisRequest` input, a fixed mock LlmClient response, and asserts the exact `AnalysisResponse` output fields match expected values.
7. THE System SHALL include a `@Disabled` smoke test (`OpenAiSmokeTest`) that makes a real OpenAI API call for manual schema drift verification.

---

### Requirement 11: Service-Level Timeout

**User Story:** As a system operator, I want the service layer to enforce an overall deadline on the full analysis pipeline, so that slow LLM responses do not hold threads indefinitely even if the HTTP client timeout is misconfigured.

#### Acceptance Criteria

1. THE Service SHALL enforce a configurable overall timeout on the full `analyze` call (prompt build + LLM call + validation) using `Mono.timeout()` on the reactive chain, which cancels the underlying HTTP request when exceeded.
2. IF the overall service timeout is exceeded, THEN THE Service SHALL throw `LlmTimeoutException`, which the ExceptionHandler maps to HTTP 504.
3. THE service-level timeout SHALL be sourced from a configurable property (`llm.openai.service-timeout-seconds`, default `70`) and SHALL be validated at startup to be greater than `timeout-seconds * 2`.

---

### Requirement 12: Prompt Injection Awareness

**User Story:** As a system operator, I want the system to be aware of prompt injection risks from user-supplied code, so that the risk is documented and a basic mitigation is in place for v1.

#### Acceptance Criteria

1. THE PromptBuilder SHALL place the user-supplied `code` value in a clearly delimited section of the prompt between `--- BEGIN CODE ---` and `--- END CODE ---` markers to reduce the risk of the code content being interpreted as prompt instructions.
2. THE System documentation SHALL note that full prompt injection prevention requires provider-level structured output enforcement, which is implemented via `json_schema` strict mode.

---

### Requirement 13: Observability and Metrics

**User Story:** As a system operator, I want the system to expose operational metrics so that I can monitor performance, cost, and reliability in production.

#### Acceptance Criteria

1. THE System SHALL expose a Prometheus scrape endpoint at `/actuator/prometheus` via `micrometer-registry-prometheus`.
2. THE System SHALL record the following Micrometer metrics:
   - `llm.analysis.requests` (Counter, tags: `status`, `model`) — total requests by outcome
   - `llm.analysis.latency` (Timer with p50/p95/p99 histogram, tags: `status`, `model`) — end-to-end latency
   - `llm.retries` (Counter, tag: `model`) — retry count due to validation failure
   - `llm.errors` (Counter, tag: `error_type`) — errors by type (timeout/validation/communication/refusal/auth/rate_limit)
   - `llm.tokens.prompt`, `llm.tokens.completion`, `llm.tokens.total` (DistributionSummary, tag: `model`) — token usage per request
3. ALL meters SHALL be pre-registered in the `LlmMetrics` constructor for the default model to avoid `ConcurrentHashMap` insertion on the hot path.
4. THE System SHALL expose `/actuator/health` and `/actuator/info` endpoints.

---

### Requirement 14: Performance

**User Story:** As a system operator, I want the system to be tuned for production throughput so that it handles concurrent requests efficiently without resource leaks.

#### Acceptance Criteria

1. THE WebClient connection pool SHALL be bounded with a maximum of 50 connections, 5-second pending acquire timeout, 30-second max idle time, and 60-second max lifetime.
2. THE WebClient codec buffer SHALL be set to at least 2 MB to prevent `DataBufferLimitException` on large LLM responses.
3. THE WebClient SHALL apply a 5-second TCP connect timeout independently of the HTTP response timeout.
4. THE `LlmResponseValidator.validate()` call SHALL be offloaded to `Schedulers.boundedElastic()` to avoid blocking the Netty event loop thread.
5. THE `AnalysisResponseSchemaFactory` SHALL build the `response_format.json_schema` JSON once at construction time and cache it as a string, avoiding repeated node-tree construction per request.
6. THE Tomcat thread pool SHALL be configured with a maximum of 50 threads and a minimum of 5 spare threads.
7. HTTP response compression SHALL be enabled for `application/json` responses ≥ 1 KB.

---

### Requirement 15: Containerisation

**User Story:** As a developer, I want the application to be containerisable for local development and CI/CD deployment.

#### Acceptance Criteria

1. THE System SHALL provide a multi-stage `Dockerfile` that produces a minimal JRE-only runtime image running as a non-root user.
2. THE `Dockerfile` SHALL include a `HEALTHCHECK` directive using the `/actuator/health` endpoint.
3. THE System SHALL provide a `jib-maven-plugin` (version 3.5.1) configuration in `pom.xml` for building Docker images without a Docker daemon.
4. THE System SHALL provide a `.dockerignore` file that excludes secrets, `.env` files, `.kiro/`, and `.git/` from the Docker build context.

---

### Requirement 16: Circuit Breaker

**User Story:** As a system operator, I want the system to stop sending requests to OpenAI when it is consistently failing, so that the API fails fast rather than waiting for timeouts on every request.

#### Acceptance Criteria

1. THE System SHALL wrap all `LlmClient.call()` invocations with a Resilience4j circuit breaker named `llm-openai`.
2. WHEN the failure rate exceeds 50% over a 10-call sliding window, THE circuit breaker SHALL open and subsequent calls SHALL fail immediately with `LlmCircuitOpenException` (HTTP 503).
3. THE circuit breaker SHALL remain open for 30 seconds before transitioning to half-open to probe recovery.
4. `LlmAuthException` and `LlmRateLimitException` SHALL NOT count as failures for circuit breaker purposes.
5. Circuit breaker state and metrics SHALL be published to Micrometer under `resilience4j.circuitbreaker.*`.

---

### Requirement 17: Global Rate Limiting

**User Story:** As a system operator, I want to limit the rate of incoming requests to protect the OpenAI budget and prevent runaway traffic from exhausting the API quota.

#### Acceptance Criteria

1. THE System SHALL enforce a global rate limit on all requests to `POST /api/v1/code/analyze` using a Bucket4j token bucket filter.
2. WHEN the token bucket is empty, THE System SHALL reject the request immediately with HTTP 429 and a `Retry-After: 1` response header.
3. THE rate limit SHALL be configurable via `rate-limit.requests-per-second` (default `10`) and `rate-limit.burst-capacity` (default `20`), both overridable via environment variables.
4. THE rate limiter SHALL be disableable via `rate-limit.enabled=false` for testing and development.
5. THE rate limiter SHALL run at `HIGHEST_PRECEDENCE + 1` — after `RequestIdFilter` so that rate-limited requests still receive a `requestId` for log correlation.

---

## Known Limitations and Future Enhancements

The following items are pending for future iterations.

### FL-2: Confidence Field Deferred

The `confidence` field was removed from v1 because there is no defined computation source.

**Future:** Compute via heuristic or ask the LLM to self-report a confidence score in the output schema.

### FL-8: No RAG (Retrieval-Augmented Generation)

The current system analyzes code purely from the LLM's training data. There is no mechanism to augment the prompt with project-specific context such as team coding standards, architecture decision records, known bug patterns, or previous analysis results.

**Future:** Add a RAG layer using Spring AI's `VectorStore` and `EmbeddingModel` abstractions:
- Embed code snippets and project documents using OpenAI `text-embedding-3-small` or a local model
- Store embeddings in a vector store (pgvector, Weaviate, or Pinecone)
- Add a `RagRetriever` component that retrieves relevant context for each analysis request
- Add a `PromptEnricher` that merges retrieved context into the prompt before the LLM call

The pipeline becomes: `PromptBuilder → RagRetriever → PromptEnricher → LlmClient → Validator`

The `LlmClient` interface and `LlmResponseValidator` do not change. Spring AI (compatible with Spring Boot 4) provides first-class RAG support.
