# 🧠 Project Overview

---

## 📋 Documentation Maintenance Rules

**Any agent or developer implementing a new feature, fix, or enhancement MUST update all relevant documentation before the work is considered complete.**

### Files to keep in sync

| File | Update when |
|---|---|
| `agents.MD` | Any change to architecture, components, config, tech stack, HTTP codes, failure handling, or testing strategy |
| `.kiro/specs/ai-dev-assistant/requirements.md` | New requirements, changed acceptance criteria, resolved or new known limitations |
| `.kiro/specs/ai-dev-assistant/design.md` | New components, changed data flow, updated exception hierarchy, new dependencies |
| `.kiro/specs/ai-dev-assistant/tasks.md` | New tasks added, task status changed (mark `[x]` when complete) |
| `openapi.yaml` | New endpoints, new/changed request or response fields, new HTTP response codes |
| `application.properties` | New configuration properties (must include ENV variable override and default value) |
| `Dockerfile` / `.dockerignore` | Changes to runtime dependencies, JVM flags, or build process |
| `docs/architecture.md` | New components, changed data flow, new resilience layers, threading model changes |
| `docs/dependencies.md` | New dependencies added or removed (include version and reason) |
| `docs/observability.md` | New metrics, new log events, new health indicators |
| `docs/development.md` | New build steps, new test types, new common issues |
| `docs/adr/` | Any significant architectural decision — create a new ADR file |
| `README.md` | New ENV variables, new HTTP codes, new run options, new troubleshooting entries |

### Checklist for every feature

Before marking any feature complete, verify:

- [ ] New classes/packages are reflected in the project structure tree in `agents.MD`
- [ ] New HTTP status codes are documented in `agents.MD`, `openapi.yaml`, and `README.md`
- [ ] New configuration properties have ENV variable names, defaults, and descriptions in `agents.MD`, `application.properties`, and `README.md`
- [ ] New exceptions are listed in the exception hierarchy in `agents.MD` and `design.md`
- [ ] New components have a named section in `design.md` and `docs/architecture.md`
- [ ] New dependencies are documented in `docs/dependencies.md` with version and reason
- [ ] New metrics or log events are documented in `docs/observability.md`
- [ ] New requirements are added to `requirements.md` with acceptance criteria
- [ ] Known limitations that are resolved are removed from `agents.MD`, `requirements.md`, and `design.md`
- [ ] New known limitations are added to all three docs
- [ ] `tasks.md` task statuses are accurate (`[ ]` vs `[x]`)
- [ ] Significant architectural decisions have a new ADR in `docs/adr/`
- [ ] `README.md` troubleshooting section is updated if the feature introduces new failure modes

---

A backend-first, AI-powered developer assistant built with Spring Boot.

The system exposes a REST API that:
- Accepts code snippets and a programming language
- Constructs structured prompts and delegates to an LLM provider (OpenAI)
- Enforces strict JSON output via OpenAI's `json_schema` structured output mode
- Validates the LLM response before returning it to the caller
- Returns deterministic, schema-compliant insights

**This is not a chatbot. It is a deterministic, single-turn analysis pipeline where the LLM is treated as an untrusted external component.**

---

## 🎯 Goals
- Production-grade LLM-backed REST API
- Strict structured JSON output enforced at the provider level (`json_schema`)
- Reliability: validation, retry, timeout handling at both HTTP and service levels
- Full observability: structured logging (MDC/requestId), Micrometer metrics, Prometheus scrape endpoint
- Performance: bounded connection pool, pre-registered meters, cached schema, non-blocking validation
- Modular and extensible architecture

## 🚫 Non-Goals (v1)
- No UI / frontend
- No agent orchestration or multi-step reasoning
- No vector DB / RAG
- No streaming responses

---

## 🧱 Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.0.6 |
| HTTP client | Spring WebFlux `WebClient` (reactive, bounded connection pool) |
| JSON | Jackson |
| Validation | Jakarta Bean Validation |
| Boilerplate | Lombok |
| Build | Maven |
| API contract | OpenAPI 3.x (`openapi.yaml`) + `openapi-generator-maven-plugin` 7.22.0 |
| Metrics | Micrometer + Prometheus (`/actuator/prometheus`) |
| Containerisation | Dockerfile (multi-stage) + Jib Maven plugin |
| Testing | JUnit 5, Mockito, jqwik (property-based), MockWebServer (OkHttp) |

---

## 📦 Project Structure

```
ai-dev-assistant/
├── src/main/java/com/aidevassistant/
│   ├── AiDevAssistantApplication.java         ← Entry point
│   ├── config/
│   │   ├── LlmProperties.java                 ← @ConfigurationProperties (llm.openai.*)
│   │   │                                         with @PostConstruct cross-field validation
│   │   ├── WebClientConfig.java               ← OpenAI WebClient: bounded pool, 2MB codec,
│   │   │                                         5s TCP connect timeout
│   │   ├── AsyncConfig.java                   ← ThreadPoolTaskExecutor + MdcTaskDecorator
│   │   ├── LlmCircuitBreakerConfig.java        ← Resilience4j circuit breaker bean
│   │   ├── RateLimitProperties.java            ← @ConfigurationProperties (rate-limit.*)
│   │   └── LlmMetrics.java                    ← Micrometer meters (pre-registered in constructor)
│   ├── controller/
│   │   └── CodeAnalysisController.java        ← Implements generated ApiApi interface
│   ├── dto/                                   ← Generated from openapi.yaml (do not hand-author)
│   │   └── (AnalysisRequest, AnalysisResponse, ErrorResponse)
│   ├── llm/
│   │   ├── LlmClient.java                     ← Interface: Mono<String> call(String prompt)
│   │   ├── OpenAiClient.java                  ← Reactive WebClient implementation
│   │   ├── AnalysisResponseSchemaFactory.java ← Builds json_schema block; schema cached as
│   │   │                                         JSON string at construction time
│   │   └── TokenUsage.java                    ← Immutable record for token usage metadata
│   ├── prompt/
│   │   └── PromptBuilder.java                 ← Builds structured prompts with code delimiters;
│   │                                             enforces max prompt character limit
│   ├── service/
│   │   └── CodeAnalysisService.java           ← Orchestrates pipeline + metrics + retry;
│   │                                             validation offloaded to boundedElastic scheduler
│   ├── validator/
│   │   └── LlmResponseValidator.java          ← Extracts + validates LLM JSON response
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java        ← @RestControllerAdvice, maps all exceptions
│   │   ├── LlmTimeoutException.java           ← HTTP 504
│   │   ├── LlmValidationException.java        ← HTTP 502
│   │   ├── LlmResponseFailureException.java   ← HTTP 502 (after retry exhausted)
│   │   ├── LlmCommunicationException.java     ← HTTP 500
│   │   ├── LlmAuthException.java              ← HTTP 500 (invalid API key)
│   │   ├── LlmRateLimitException.java         ← HTTP 429 (OpenAI rate limit)
│   │   ├── LlmRefusalException.java           ← HTTP 422 (model content refusal)
│   │   ├── LlmCircuitOpenException.java       ← HTTP 503 (circuit breaker open)
│   │   ├── RateLimitExceededException.java    ← HTTP 429 (global rate limit)
│   │   └── PromptTooLargeException.java       ← HTTP 400 (prompt exceeds max chars)
│   └── util/
│       ├── RequestIdFilter.java               ← Assigns UUID requestId to every request via MDC
│       ├── RateLimitFilter.java               ← Global token bucket rate limiter (Bucket4j)
│       └── MdcTaskDecorator.java              ← Propagates MDC context into async threads
├── src/test/java/com/aidevassistant/
│   ├── config/LlmMetricsTest.java             ← Unit tests for all Micrometer meters
│   ├── llm/OpenAiClientTest.java              ← MockWebServer tests (HTTP errors, schema, tokens,
│   │                                             refusal, json_schema enforcement)
│   ├── llm/OpenAiSmokeTest.java               ← @Disabled real API smoke test
│   ├── prompt/PromptBuilderTest.java          ← Unit + jqwik property tests + injection tests
│   ├── validator/LlmResponseValidatorTest.java ← Unit + jqwik property tests
│   ├── service/CodeAnalysisServiceTest.java   ← Unit tests (retry, timeout, metrics)
│   ├── util/RateLimitFilterTest.java          ← Unit tests for rate limiter (pass/reject/disabled)
│   └── integration/
│       ├── CodeAnalysisIntegrationTest.java   ← Full Spring context + MockMvc
│       │                                         (HTTP 200, 400, 502, 504, X-Request-Id)
│       ├── GoldenTest.java                    ← Fixed input → exact output regression test
│       └── ApplicationContextTest.java        ← Startup validation (valid/missing API key)
├── Dockerfile                                 ← Multi-stage build (Maven builder + JRE runtime),
│                                                 non-root user, HEALTHCHECK
├── .dockerignore                              ← Whitelist approach; excludes secrets and .kiro/
├── openapi.yaml                               ← API contract (single source of truth for DTOs)
├── .gitignore                                 ← Excludes target/, .jqwik-database, .env, secrets
├── agents.MD                                  ← This file
└── pom.xml
```

---

## 🔌 API Specification

### `POST /api/v1/code/analyze`

**Request headers**

| Header | Required | Description |
|---|---|---|
| `Content-Type` | Yes | `application/json` |
| `X-Request-Id` | No | Client-supplied trace ID. Echoed back in response. If absent, a UUID v4 is generated. |

**Request body**
```json
{
  "code": "string (required, minLength: 1, maxLength: 50000)",
  "language": "string (required, minLength: 1, maxLength: 100)"
}
```

**Response (HTTP 200)**
```json
{
  "summary": "string",
  "issues": ["string"],
  "improvements": ["string"]
}
```

**Error Response (all error codes)**
```json
{
  "error": "ExceptionClassName",
  "message": "Human-readable description",
  "requestId": "uuid-assigned-to-this-request"
}
```

**HTTP status codes**

| Code | Cause |
|---|---|
| 200 | Success |
| 400 | Blank/missing field, or prompt exceeds max character limit |
| 422 | LLM refused to respond (content policy) |
| 429 | Global rate limit exceeded, or OpenAI rate limit exceeded |
| 502 | LLM response failed validation after retry |
| 503 | Circuit breaker open — too many recent LLM failures, retry after ~30s |
| 504 | LLM or service-level timeout |
| 500 | Internal error (network failure, invalid API key, unexpected exception) |

---

## 🧠 Core Components

### 1. Controller (`CodeAnalysisController`)
Implements the generated `ApiApi` interface from `openapi.yaml`. Accepts the `X-Request-Id` header parameter (required by the generated interface) but does not use it — `RequestIdFilter` already handles it before the controller is invoked. Delegates entirely to `CodeAnalysisService`.

### 2. Service (`CodeAnalysisService`)
Orchestrates the full pipeline:
```
PromptBuilder.build(request)
  → LlmClient.call(prompt)                    [reactive Mono, service-level timeout]
  → validator.validate(raw)                   [offloaded to Schedulers.boundedElastic()]
  → AnalysisResponse
```
- Applies a **service-level deadline** (`llm.openai.service-timeout-seconds`) via `Mono.timeout()` — cancels the underlying HTTP request on expiry, no connection leaks
- **Retries once** on `LlmValidationException`; never retries on timeout or communication errors
- Offloads synchronous JSON validation to `Schedulers.boundedElastic()` to avoid blocking the Netty event loop
- Records **Micrometer metrics**: request counter, latency timer, retry counter, error counter by type

### 3. PromptBuilder
Builds a deterministic prompt that:
- Defines the role, task, and required JSON schema
- Wraps user code between `--- BEGIN CODE ---` / `--- END CODE ---` delimiters (reduces prompt injection risk)
- Enforces a configurable **max prompt character limit** (`llm.openai.max-prompt-chars`, default 12,000 chars ≈ 3,000 tokens) — rejects oversized requests with HTTP 400 before any LLM call

### 4. LlmClient / OpenAiClient
`LlmClient` is a reactive interface: `Mono<String> call(String prompt)`.

`OpenAiClient` implementation:
- Fully reactive WebClient pipeline — `Mono.timeout()` cancels the HTTP request, not just the thread
- Sends `response_format.type = "json_schema"` with the full `AnalysisResponse` schema (strict mode, `additionalProperties: false`) — enforces structured output at the provider level
- Detects `choices[0].message.refusal` and throws `LlmRefusalException` (HTTP 422)
- Parses token usage (`prompt_tokens`, `completion_tokens`, `total_tokens`) and records to Micrometer distribution summaries
- Maps HTTP errors: 401 → `LlmAuthException`, 429 → `LlmRateLimitException`, others → `LlmCommunicationException`

### 5. AnalysisResponseSchemaFactory
Builds the `response_format.json_schema` block injected into every OpenAI request.

**Performance:** The schema is static and never changes. It is serialized to a JSON string once in the constructor and cached. `addResponseFormat()` parses the cached string — one `readTree()` call instead of ~20 `putObject()`/`put()` node operations per request.

### 6. WebClientConfig
Configures the `openAiWebClient` bean with production-grade settings:
- **Connection pool** (`openai-pool`): max 50 connections, 5s pending acquire timeout, 30s max idle, 60s max lifetime, background eviction every 30s
- **Codec buffer**: raised to 2 MB (default 256 KB causes `DataBufferLimitException` on large responses)
- **TCP connect timeout**: 5s (independent of HTTP response timeout)

### 7. LlmMetrics
Centralises all Micrometer metric definitions. All meters for the default model (`gpt-4o`) are **pre-registered in the constructor** to avoid `ConcurrentHashMap` insertion on the hot path. Subsequent calls return the cached meter instance directly.

### 8. LlmResponseValidator
Defensive validation layer — **kept even with `json_schema` strict mode** because providers can still return malformed payloads, partial responses, or change behaviour across API versions.

Steps:
1. Reject blank/empty input
2. Strip markdown code fences
3. Extract outermost JSON object (first `{` / last `}`)
4. Parse with Jackson
5. Verify `summary` (string), `issues` (array of strings), `improvements` (array of strings)
6. Map to `AnalysisResponse` DTO

### 9. Exception Handling (`GlobalExceptionHandler`)
`@RestControllerAdvice` maps every exception type to a structured `ErrorResponse` containing `error`, `message`, and `requestId` (from MDC). All exception classes are in the same package — no fully-qualified names needed.

### 10. Circuit Breaker (`LlmCircuitBreakerConfig`)
Wraps all `LlmClient.call()` invocations with a Resilience4j circuit breaker (`llm-openai`):
- **Closed (normal):** calls pass through; failure rate tracked over 10-call sliding window
- **Open (tripped):** calls fail immediately with `LlmCircuitOpenException` (HTTP 503); stays open 30s
- **Half-open (probing):** 3 test calls allowed; success → closes, failure → opens again
- Ignores `LlmAuthException` and `LlmRateLimitException` (not transient failures)
- Circuit breaker metrics published to Micrometer under `resilience4j.circuitbreaker.*`

### 11. Rate Limiting (`RateLimitFilter`)
Global token bucket rate limiter using Bucket4j, running at `HIGHEST_PRECEDENCE + 1`:
- **Algorithm:** token bucket with greedy refill — smooth behaviour under bursty traffic
- **Default:** 10 requests/second steady-state, burst capacity of 20
- **On limit exceeded:** throws `RateLimitExceededException` → HTTP 429 with `Retry-After: 1` header
- **Configurable:** `rate-limit.enabled`, `rate-limit.requests-per-second`, `rate-limit.burst-capacity`
- **Storage:** in-memory (single instance); replace with distributed Bucket4j backend for multi-instance

### 10. Observability

**Request ID tracking:**
- `RequestIdFilter` runs at `Ordered.HIGHEST_PRECEDENCE` on every request
- Reads `X-Request-Id` header or generates a UUID v4
- Stores in SLF4J MDC under key `requestId`
- Echoes back in `X-Request-Id` response header
- `MdcTaskDecorator` propagates MDC into async threads

**Log pattern:**
```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{requestId:-unknown}] [${PID:-}] %level::%logger{0} %msg%n
```

**Micrometer metrics (Prometheus at `/actuator/prometheus`):**

| Metric | Type | Tags |
|---|---|---|
| `llm.analysis.requests` | Counter | `status` (success/failure), `model` |
| `llm.analysis.latency` | Timer (p50/p95/p99) | `status`, `model` |
| `llm.retries` | Counter | `model` |
| `llm.errors` | Counter | `error_type` (timeout/validation/communication/refusal/auth/rate_limit/**circuit_open**) |
| `llm.tokens.prompt` | DistributionSummary | `model` |
| `llm.tokens.completion` | DistributionSummary | `model` |
| `llm.tokens.total` | DistributionSummary | `model` |

---

## ⚙️ Configuration

All properties are in `application.properties` and can be overridden via environment variables.

### LLM / OpenAI

| Property | ENV Variable | Default | Description |
|---|---|---|---|
| `llm.openai.api-key` | `LLM_OPENAI_API_KEY` | *(required)* | OpenAI API key — app fails to start if blank |
| `llm.openai.model` | `LLM_OPENAI_MODEL` | `gpt-4o` | Model name |
| `llm.openai.max-tokens` | `LLM_OPENAI_MAX_TOKENS` | `1024` | Max completion tokens |
| `llm.openai.timeout-seconds` | `LLM_OPENAI_TIMEOUT_SECONDS` | `30` | HTTP client timeout per call |
| `llm.openai.service-timeout-seconds` | `LLM_OPENAI_SERVICE_TIMEOUT_SECONDS` | `70` | Service-level deadline — must be `> timeout-seconds * 2` |
| `llm.openai.max-prompt-chars` | `LLM_OPENAI_MAX_PROMPT_CHARS` | `12000` | Max prompt length before HTTP 400 |

> **Cross-field validation:** `service-timeout-seconds` must be greater than `timeout-seconds * 2`. The app fails to start with a descriptive error if this constraint is violated.

### Logging

| Property | ENV Variable | Default | Description |
|---|---|---|---|
| `logging.level.root` | `LOGGING_LEVEL_ROOT` | `INFO` | Root log level |
| `logging.level.com.aidevassistant` | `LOGGING_LEVEL_COM_AIDEVASSISTANT` | `INFO` | Set to `DEBUG` for prompt/response logs |

### Server (Tomcat)

| Property | ENV Variable | Default | Description |
|---|---|---|---|
| `server.compression.enabled` | `SERVER_COMPRESSION_ENABLED` | `true` | HTTP response compression |
| `server.tomcat.connection-timeout` | `SERVER_TOMCAT_CONNECTION_TIMEOUT` | `10s` | Max wait for client to send request |
| `server.tomcat.keep-alive-timeout` | `SERVER_TOMCAT_KEEP_ALIVE_TIMEOUT` | `30s` | Idle HTTP connection lifetime |
| `server.tomcat.threads.max` | `SERVER_TOMCAT_THREADS_MAX` | `50` | Max request processing threads |
| `server.tomcat.threads.min-spare` | `SERVER_TOMCAT_THREADS_MIN_SPARE` | `5` | Min idle threads (avoids cold-start latency) |

### Rate Limiting

| Property | ENV Variable | Default | Description |
|---|---|---|---|
| `rate-limit.enabled` | `RATE_LIMIT_ENABLED` | `true` | Enable/disable rate limiting |
| `rate-limit.requests-per-second` | `RATE_LIMIT_REQUESTS_PER_SECOND` | `10` | Steady-state refill rate |
| `rate-limit.burst-capacity` | `RATE_LIMIT_BURST_CAPACITY` | `20` | Max burst tokens (2× steady-state) |

### Actuator / Metrics

| Property | ENV Variable | Default | Description |
|---|---|---|---|
| `management.endpoints.web.exposure.include` | `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,prometheus,metrics` | Exposed actuator endpoints |
| `management.endpoint.health.show-details` | `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS` | `always` | Full health details |
| `management.metrics.tags.application` | `MANAGEMENT_METRICS_TAGS_APPLICATION` | `ai-dev-assistant` | Common tag on all metrics |

---

## 🔐 Prompt Design

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

The `--- BEGIN CODE ---` / `--- END CODE ---` delimiters make the boundary between instructions and user-supplied content explicit, reducing prompt injection risk. The `json_schema` structured output mode at the provider level is the primary enforcement mechanism.

---

## ⚠️ Failure Handling

| Scenario | Behaviour |
|---|---|
| LLM returns invalid JSON | Retry once with the same prompt |
| LLM returns invalid JSON on retry | Throw `LlmResponseFailureException` → HTTP 502 |
| LLM returns a refusal | Throw `LlmRefusalException` → HTTP 422 |
| Circuit breaker open | Throw `LlmCircuitOpenException` → HTTP 503, retry after ~30s |
| Global rate limit exceeded | Throw `RateLimitExceededException` → HTTP 429 with `Retry-After: 1` |
| HTTP client timeout | Throw `LlmTimeoutException` → HTTP 504 (no retry) |
| Service-level deadline exceeded | Throw `LlmTimeoutException` → HTTP 504, HTTP request cancelled |
| HTTP 401 | Throw `LlmAuthException` → HTTP 500 (config error) |
| HTTP 429 | Throw `LlmRateLimitException` → HTTP 429 |
| Prompt too large | Throw `PromptTooLargeException` → HTTP 400 (before any LLM call) |
| `service-timeout-seconds ≤ timeout-seconds * 2` | App fails to start with descriptive error |

---

## 🧪 Testing Strategy

| Test type | Scope | Tool |
|---|---|---|
| Unit | `PromptBuilder`, `LlmResponseValidator`, `CodeAnalysisService`, `LlmMetrics`, `RateLimitFilter` | JUnit 5, Mockito |
| Property-based | `PromptBuilder` (3 properties, 50 tries), `LlmResponseValidator` (4 properties, 50 tries) | jqwik |
| HTTP client | `OpenAiClient` — success, HTTP errors, refusal, timeout, `json_schema` enforcement, token usage | JUnit 5 + MockWebServer |
| Integration | `POST /api/v1/code/analyze` — HTTP 200, 400, 502, 504, `X-Request-Id` propagation | Spring Boot Test + MockMvc |
| Golden | Fixed input → exact output regression guard | JUnit 5 |
| Startup | Context loads with valid key; fails with descriptive error without key | Spring Boot Test |
| Smoke | Real OpenAI API call (`@Disabled` — run manually only) | JUnit 5 |

---

## 🧠 Design Principles
- **LLM is untrusted** — always validate output regardless of provider-level schema enforcement
- **Reactive pipeline** — `Mono.timeout()` cancels HTTP requests on deadline, no connection leaks
- **Non-blocking validation** — synchronous JSON parsing runs on `Schedulers.boundedElastic()`, not the Netty event loop
- **Deterministic flows** — temperature fixed at 0.2, structured output enforced via `json_schema`
- **Observability first** — every request has a `requestId` in all logs and the response header
- **Fail fast on config errors** — missing API key and invalid timeout ratios prevent startup
- **OpenAPI-driven** — `openapi.yaml` is the single source of truth for DTOs and controller interfaces
- **Pre-warm hot paths** — meters pre-registered, schema cached, patterns compiled at class load

---

## 🚀 Running the Application

### Local (Maven)
```bash
export LLM_OPENAI_API_KEY=sk-...
mvn spring-boot:run
```

### Docker (Dockerfile)
```bash
docker build -t ai-dev-assistant .
docker run -p 8080:8080 -e LLM_OPENAI_API_KEY=sk-... ai-dev-assistant
```

### Docker (Jib — no Dockerfile needed)
```bash
# Build to local Docker daemon
mvn jib:dockerBuild

# Run
docker run -p 8080:8080 -e LLM_OPENAI_API_KEY=sk-... ai-dev-assistant:latest
```

### Verify
```bash
# Analyze a code snippet
curl -X POST http://localhost:8080/api/v1/code/analyze \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: my-trace-id" \
  -d '{"code": "public class Hello { public static void main(String[] args) { System.out.println(\"Hello\"); } }", "language": "Java"}'

# Check Prometheus metrics
curl http://localhost:8080/actuator/prometheus | grep llm_

# Health check
curl http://localhost:8080/actuator/health
```

---

## 🔥 Known Limitations & Future Enhancements

| ID | Description | Future path |
|---|---|---|
| FL-2 | `confidence` field deferred — no defined computation source | Compute via heuristic or ask LLM to self-report |
| FL-8 | No RAG (Retrieval-Augmented Generation) | Add vector store + embedding model via Spring AI; enrich prompts with codebase patterns, style guides, or past analysis results |
