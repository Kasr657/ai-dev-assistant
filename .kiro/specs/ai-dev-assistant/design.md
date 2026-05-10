# Design Document: AI-Powered Developer Assistant

## Overview

This document describes the technical design for the AI-powered developer assistant backend. The system is a production-grade REST API built with Java 25 and Spring Boot 4.0.6. It accepts code snippets, constructs structured prompts, delegates analysis to an LLM provider (OpenAI initially), validates the structured JSON response, and returns deterministic, schema-compliant insights.

The LLM is treated as an untrusted external component. All output is validated before being returned to the caller.

---

## Architecture

### High-Level Flow

```
HTTP POST /api/v1/code/analyze
        │
        ▼
  [RequestIdFilter]     ← Assigns UUID requestId → MDC, echoes X-Request-Id header
        │
        ▼
  [RateLimitFilter]     ← Global token bucket (Bucket4j) — HTTP 429 if bucket empty
        │
        ▼
  [Controller]          ← Generated from openapi.yaml via openapi-generator-maven-plugin 7.22.0
        │
        ▼
  [Service]             ← Orchestrates the reactive analysis pipeline
        │
   ┌────┴────┐
   ▼         ▼
[PromptBuilder]   [Validator]          ← Validator runs on Schedulers.boundedElastic()
        │               ▲
        ▼               │
  [LlmClient]  ─────────┘
  (Mono<String>)
        │
        ▼
  [OpenAiClient]        ← Reactive WebClient, json_schema enforcement, token usage metrics
        │
        ▼
  [LlmMetrics]          ← Pre-registered Micrometer meters (requests, latency, retries, errors, tokens)
```

### Package Structure

```
ai-dev-assistant/
├── controller/         ← Generated from openapi.yaml (do not hand-author)
├── service/            ← CodeAnalysisService
├── llm/                ← LlmClient interface + OpenAiClient + AnalysisResponseSchemaFactory + TokenUsage
├── prompt/             ← PromptBuilder
├── dto/                ← Generated from openapi.yaml (AnalysisRequest, AnalysisResponse, ErrorResponse)
├── validator/          ← LlmResponseValidator
├── config/             ← LlmProperties, WebClientConfig, AsyncConfig, LlmMetrics,
│                          LlmCircuitBreakerConfig, RateLimitProperties
├── exception/          ← Custom exceptions + GlobalExceptionHandler
└── util/               ← RequestIdFilter, RateLimitFilter, MdcTaskDecorator
```

---

## Component Design

### 1. OpenAPI Specification (`openapi.yaml`)

The single source of truth for all API contracts. The Maven build generates Java source from this file using `openapi-generator-maven-plugin` 7.22.0.

**Endpoint:** `POST /api/v1/code/analyze`

**AnalysisRequest schema:**
- `code` (string, required, minLength: 1, maxLength: 50000)
- `language` (string, required, minLength: 1, maxLength: 100)

**AnalysisResponse schema:**
- `summary` (string, required, minLength: 1)
- `issues` (array of strings, required)
- `improvements` (array of strings, required)

> `confidence` is deferred to a future version. See Known Limitations.

**Error response schema:**
- `error` (string, required)
- `message` (string, required)
- `requestId` (string, required)

**Documented response codes:** 200, 400, 422, 429, 500, 502, 504

**Headers:** `X-Request-Id` documented as optional request header and present on all responses.

### 2. Controller Layer

Generated from `openapi.yaml` via `openapi-generator-maven-plugin`. The application provides a `@RestController` that implements the generated `ApiApi` interface and delegates to `CodeAnalysisService`.

The generated interface includes an `xRequestId` parameter (from the `X-Request-Id` header definition in `openapi.yaml`). The controller accepts it to satisfy the interface contract but does not use it — `RequestIdFilter` already handles it before the controller is invoked.

```java
@RestController
@RequiredArgsConstructor
public class CodeAnalysisController implements ApiApi {

    private final CodeAnalysisService service;

    @Override
    public ResponseEntity<AnalysisResponse> analyzeCode(AnalysisRequest request, String xRequestId) {
        return ResponseEntity.ok(service.analyze(request));
    }
}
```

### 3. Service Layer (`CodeAnalysisService`)

Orchestrates the full analysis pipeline using a reactive chain. The service-level deadline is applied via `Mono.timeout()` which cancels the underlying HTTP request on expiry — no connection leaks.

```
analyze(request):
  requestId = MDC.get("requestId")
  prompt = promptBuilder.build(request)   // throws PromptTooLargeException if too long
  latencySample = llmMetrics.startLatencyTimer()

  buildPipeline(prompt, requestId, model)
    .timeout(serviceTimeoutSeconds)
    .block()

buildPipeline(prompt, requestId, model):
  llmClient.call(prompt)                  // Mono<String>, reactive
    .doOnSubscribe → restore MDC
    .doOnTerminate → clear MDC
    .flatMap(raw → validator.validate(raw)  // offloaded to Schedulers.boundedElastic()
    .onErrorResume(LlmValidationException):
        llmMetrics.incrementRetries(model)
        retry once → same pipeline
        if retry also fails → throw LlmResponseFailureException
    .onErrorMap(LlmTimeoutException) → log ERROR
    .onErrorMap(LlmCommunicationException) → log ERROR
```

Metrics recorded on every call:
- `llm.analysis.latency` timer (start before pipeline, stop on outcome)
- `llm.analysis.requests` counter (status=success or failure, model tag)
- `llm.retries` counter (on first validation failure)
- `llm.errors` counter (error_type tag: timeout/validation/communication/refusal/auth/rate_limit)

### 4. PromptBuilder

Produces a deterministic, structured prompt. Enforces a configurable max character limit (`llm.openai.max-prompt-chars`, default 12,000 chars ≈ 3,000 tokens).

**Prompt template:**

```
You are a senior backend engineer.

Analyze the following {language} code and return ONLY a strict JSON object.

Required JSON schema:
{
  "summary": "<string: one-sentence summary>",
  "issues": ["<string>", ...],
  "improvements": ["<string>", ...]
}

Rules:
- Output ONLY the JSON object. No markdown, no explanation, no text outside the JSON.
- Be concise and accurate.

--- BEGIN CODE ---
{code}
--- END CODE ---
```

The `--- BEGIN CODE ---` / `--- END CODE ---` delimiters make the boundary between instructions and user-supplied content explicit, reducing prompt injection risk. See Known Limitations (FL-6).

### 5. LlmClient Interface

```java
public interface LlmClient {
    Mono<String> call(String prompt);
}
```

Returns a cold `Mono<String>` — the HTTP call is not made until subscribed. This enables `Mono.timeout()` to cancel the underlying HTTP request when the deadline fires.

### 6. OpenAiClient

Implements `LlmClient` using a fully reactive Spring WebClient pipeline. Configuration is sourced from `LlmProperties`.

```
// Configuration properties (all overridable via ENV variables)
llm.openai.api-key=              // required — LLM_OPENAI_API_KEY
llm.openai.model=gpt-4o          // LLM_OPENAI_MODEL
llm.openai.max-tokens=1024       // LLM_OPENAI_MAX_TOKENS
llm.openai.timeout-seconds=30    // LLM_OPENAI_TIMEOUT_SECONDS (HTTP client timeout)
llm.openai.service-timeout-seconds=70  // LLM_OPENAI_SERVICE_TIMEOUT_SECONDS (must be > timeout * 2)
llm.openai.max-prompt-chars=12000 // LLM_OPENAI_MAX_PROMPT_CHARS
```

Key behaviours:
- Sends `response_format.type = "json_schema"` with the full `AnalysisResponse` schema (strict mode, `additionalProperties: false`) — enforces structured output at the provider level
- Schema is built once by `AnalysisResponseSchemaFactory` and cached as a JSON string
- Detects `choices[0].message.refusal` → throws `LlmRefusalException` (HTTP 422)
- Maps HTTP 401 → `LlmAuthException`, HTTP 429 → `LlmRateLimitException`, others → `LlmCommunicationException`
- Parses `TokenUsage` from response `usage` field and records to Micrometer via `LlmMetrics.recordTokenUsage()`
- Token usage logged at DEBUG level

### 7. AnalysisResponseSchemaFactory

Builds the `response_format.json_schema` block injected into every OpenAI request.

**Performance:** The schema is static. It is serialized to a JSON string once in the constructor (`buildResponseFormatJson()`). `addResponseFormat(ObjectNode root)` parses the cached string — one `readTree()` call instead of ~20 `putObject()`/`put()` node operations per request.

### 8. WebClientConfig

Configures the `openAiWebClient` bean with production-grade settings:

| Setting | Value | Reason |
|---|---|---|
| Connection pool max | 50 | Prevents unbounded growth |
| Pending acquire timeout | 5s | Fail fast, don't queue indefinitely |
| Max idle time | 30s | Release before server closes |
| Max lifetime | 60s | Rotate to avoid stale TCP state |
| Codec buffer | 2 MB | Default 256 KB causes `DataBufferLimitException` |
| TCP connect timeout | 5s | Independent of HTTP response timeout |

### 9. LlmResponseValidator

Extracts and validates the JSON object from the raw LLM response string. Runs on `Schedulers.boundedElastic()` to avoid blocking the Netty event loop.

**Algorithm:**
1. Check for blank/empty input → throw `LlmValidationException("Empty LLM response")`
2. Strip markdown code fences
3. Extract the outermost JSON object using first `{` / last `}` scan — **known limitation FL-3**
4. Parse with Jackson `ObjectMapper` (injected singleton)
5. Verify `summary` is a non-null string
6. Verify `issues` is a non-null array of strings
7. Verify `improvements` is a non-null array of strings
8. Map to `AnalysisResponse` DTO and return

### 10. Exception Hierarchy

```
RuntimeException
├── RateLimitExceededException   → HTTP 429 (global rate limit — Bucket4j)
├── PromptTooLargeException      → HTTP 400 (prompt exceeds max chars)
├── LlmRefusalException          → HTTP 422 (model content refusal)
├── LlmRateLimitException        → HTTP 429 (OpenAI rate limit)
├── LlmCircuitOpenException      → HTTP 503 (circuit breaker open)
├── LlmTimeoutException          → HTTP 504 (HTTP or service-level timeout)
├── LlmValidationException       → HTTP 502 (response schema mismatch)
├── LlmResponseFailureException  → HTTP 502 (after retry exhausted)
├── LlmAuthException             → HTTP 500 (invalid API key — config error)
└── LlmCommunicationException    → HTTP 500 (network/HTTP error)
```

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps each type to the appropriate HTTP status and a structured `ErrorResponse` body containing `error`, `message`, and `requestId` (from MDC).

### 11. LlmMetrics

Centralises all Micrometer metric definitions. All meters for the default model (`gpt-4o`) are **pre-registered in the constructor** to avoid `ConcurrentHashMap` insertion on the hot path.

| Metric | Type | Tags |
|---|---|---|
| `llm.analysis.requests` | Counter | `status`, `model` |
| `llm.analysis.latency` | Timer (p50/p95/p99) | `status`, `model` |
| `llm.retries` | Counter | `model` |
| `llm.errors` | Counter | `error_type` (timeout/validation/communication/refusal/auth/rate_limit/circuit_open) |
| `llm.tokens.prompt` | DistributionSummary | `model` |
| `llm.tokens.completion` | DistributionSummary | `model` |
| `llm.tokens.total` | DistributionSummary | `model` |

Circuit breaker metrics are published separately under `resilience4j.circuitbreaker.*` by `TaggedCircuitBreakerMetrics`.

### 12. Circuit Breaker (`LlmCircuitBreakerConfig`)

Wraps all `LlmClient.call()` invocations with a Resilience4j circuit breaker (`llm-openai`):

| Setting | Value | Reason |
|---|---|---|
| Sliding window | 10 calls (count-based) | Tracks recent failure rate |
| Failure rate threshold | 50% | Opens after 5 failures in 10 calls |
| Wait in open state | 30s | Gives OpenAI time to recover |
| Half-open test calls | 3 | Probes recovery before fully closing |
| Slow call threshold | 25s | Calls near the service timeout count as slow |
| Slow call rate threshold | 80% | Opens if most calls are slow |
| Ignored exceptions | `LlmAuthException`, `LlmRateLimitException` | Not transient — don't count as failures |

### 13. Rate Limiting (`RateLimitFilter`)

Global token bucket rate limiter (Bucket4j) at `HIGHEST_PRECEDENCE + 1`:

| Setting | Default | ENV Variable |
|---|---|---|
| Enabled | `true` | `RATE_LIMIT_ENABLED` |
| Requests/second | `10` | `RATE_LIMIT_REQUESTS_PER_SECOND` |
| Burst capacity | `20` | `RATE_LIMIT_BURST_CAPACITY` |

On limit exceeded: throws `RateLimitExceededException` → HTTP 429 with `Retry-After: 1` header.
Storage: in-memory (single instance). For multi-instance: replace with distributed Bucket4j backend.

### 12. Request ID and MDC

A `RequestIdFilter` (servlet filter, order = `Ordered.HIGHEST_PRECEDENCE`) runs on every request:
1. Reads `X-Request-Id` header; if absent, generates a UUID v4.
2. Stores the value in `MDC` under key `requestId`.
3. Adds `X-Request-Id` to the response headers.
4. Clears MDC in a `finally` block after the request completes.

For async tasks, `MdcTaskDecorator` propagates the MDC snapshot to the async thread. Inside the reactive pipeline, `requestId` is captured before subscription and restored via `doOnSubscribe`.

### 13. Configuration (`LlmProperties`)

```java
@ConfigurationProperties(prefix = "llm.openai")
@Validated
public class LlmProperties {
    @NotBlank String apiKey;
    String model = "gpt-4o";
    @Min(1) int maxTokens = 1024;
    @Min(1) int timeoutSeconds = 30;
    @Min(1) int serviceTimeoutSeconds = 70;  // validated: must be > timeoutSeconds * 2
    @Min(100) int maxPromptChars = 12000;
}
```

Cross-field validation via `@PostConstruct`: startup fails with a descriptive error if `serviceTimeoutSeconds <= timeoutSeconds * 2`.

### 14. Logging

Log pattern: `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{requestId:-unknown}] [${PID:-}] %level::%logger{0} %msg%n`

| Event | Level |
|---|---|
| Sanitized prompt (code replaced with `[REDACTED]`) | DEBUG |
| Raw LLM response (first 500 chars) | DEBUG |
| Parsed `AnalysisResponse` | DEBUG |
| Retry attempt | WARN |
| LLM timeout or communication error | ERROR |
| Model refusal | WARN |
| Token usage (model, prompt/completion/total tokens) | DEBUG |

---

## Data Flow

```
1. POST /api/v1/code/analyze  { code, language }
2. RequestIdFilter: read/generate requestId → MDC, set X-Request-Id response header
3. RateLimitFilter: check token bucket — HTTP 429 if empty, else consume token
4. Controller: validate request (Bean Validation via generated code), accept xRequestId param
5. Service: build prompt (enforce max chars), start latency timer
6. Service: buildPipeline(prompt, requestId, model).timeout(serviceDeadline).block()
7. CircuitBreaker: check state — HTTP 503 if open, else pass through
8. OpenAiClient: POST to OpenAI with json_schema response_format → Mono<String>
9. OpenAiClient: parse TokenUsage → record to Micrometer, check refusal, extract content
10. Validator: validate(raw) on boundedElastic → AnalysisResponse (or retry once)
11. Service: record metrics (latency, request count, error type if failed)
12. Controller: return HTTP 200 { summary, issues, improvements }
```

---

## Correctness Properties

The following universal properties must hold for all valid inputs and are validated by property-based tests (jqwik 1.9.3, 50 tries each).

**Property 1: Prompt non-blank for all valid inputs**
For all `AnalysisRequest` where `code` is non-blank and `language` is non-blank, `PromptBuilder.build(request)` must return a non-blank string.

**Property 2: Prompt contains injected language**
For all valid `AnalysisRequest`, the prompt must contain the exact `language` value.

**Property 3: Prompt contains injected code**
For all valid `AnalysisRequest`, the prompt must contain the exact `code` value.

**Property 4: Validator round-trip for valid JSON**
For all JSON strings conforming to the output schema, `Validator.validate(json)` must return an `AnalysisResponse` whose fields equal the parsed values.

**Property 5: Validator rejects all non-conforming JSON**
For all JSON strings missing at least one required field, `Validator.validate(json)` must throw `LlmValidationException`.

**Property 6: Validator rejects blank/empty input**
For all blank or empty strings, `Validator.validate(input)` must throw `LlmValidationException`.

---

## Build Configuration

**Maven plugins:**

| Plugin | Version | Purpose |
|---|---|---|
| `openapi-generator-maven-plugin` | 7.22.0 | Generates DTOs and API interfaces from `openapi.yaml` |
| `maven-compiler-plugin` | (managed by Spring Boot parent) | Java 25 source/target |
| `spring-boot-maven-plugin` | (managed by Spring Boot parent) | Executable JAR |
| `jib-maven-plugin` | 3.5.1 | Docker image build without Docker daemon |

**Key dependencies:**

| Dependency | Version | Purpose |
|---|---|---|
| `spring-boot-starter-parent` | 4.0.6 | BOM — manages all Spring versions |
| `spring-boot-starter-web` | (managed) | Tomcat + Spring MVC |
| `spring-boot-starter-validation` | (managed) | Jakarta Bean Validation |
| `spring-boot-starter-webflux` | (managed) | WebClient (reactive HTTP client) |
| `spring-boot-starter-actuator` | (managed) | Health, metrics endpoints |
| `micrometer-registry-prometheus` | (managed) | Prometheus scrape endpoint |
| `jackson-databind` | (managed) | JSON serialization |
| `lombok` | (managed) | Boilerplate reduction |
| `jackson-databind-nullable` | 0.2.10 | OpenAPI generator runtime |
| `swagger-annotations` | 2.2.49 | OpenAPI generator runtime |
| `resilience4j-reactor` | 2.4.0 | Circuit breaker for reactive pipelines |
| `resilience4j-micrometer` | 2.4.0 | Circuit breaker metrics |
| `bucket4j-core` | 8.14.0 | Token bucket rate limiting |
| `jqwik` | 1.9.3 | Property-based testing |
| `mockwebserver` (OkHttp) | 5.3.2 | WebClient HTTP client testing |

---

## Testing Strategy

| Test type | Scope | Tool | Count |
|---|---|---|---|
| Unit | `PromptBuilder`, `LlmResponseValidator`, `CodeAnalysisService`, `LlmMetrics` | JUnit 5, Mockito | ~40 tests |
| Property-based | `PromptBuilder` (3 props), `LlmResponseValidator` (4 props) | jqwik (50 tries each) | 7 properties |
| HTTP client | `OpenAiClient` — all scenarios | JUnit 5 + MockWebServer | ~15 tests |
| Integration | `POST /api/v1/code/analyze` — all HTTP codes + X-Request-Id | Spring Boot Test + MockMvc | ~8 tests |
| Golden | Fixed input → exact output | JUnit 5 | 1 test |
| Startup | Context loads / fails correctly | Spring Boot Test | 2 tests |
| Smoke | Real OpenAI API (`@Disabled`) | JUnit 5 | 1 test |

---

## Known Limitations and Future Enhancements

### FL-2: Confidence Field Deferred

Removed from v1 — no defined computation source.

**Future:** Compute via heuristic or ask the LLM to self-report.

### FL-8: No RAG (Retrieval-Augmented Generation)

The current system analyzes code purely from the LLM's training data. There is no mechanism to augment the prompt with project-specific context.

**Future:** Add a RAG layer using Spring AI:
- Embed code snippets and project documents (style guides, ADRs, known bug patterns)
- Store in a vector store (pgvector, Weaviate, or Pinecone)
- Add `RagRetriever` + `PromptEnricher` components between `PromptBuilder` and `LlmClient`
- Pipeline: `PromptBuilder → RagRetriever → PromptEnricher → LlmClient → Validator`
- `LlmClient` interface and `LlmResponseValidator` are unchanged
