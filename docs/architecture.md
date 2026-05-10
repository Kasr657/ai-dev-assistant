# Architecture

## Overview

AI Developer Assistant is a **deterministic, single-turn code analysis pipeline** backed by OpenAI. It is not a chatbot — it accepts a code snippet, runs it through a structured pipeline, and returns a validated JSON response every time.

The core design principle: **treat the LLM as an untrusted external system**. Every response is validated before it reaches the caller. The LLM can fail, hallucinate, or refuse — the system handles all of these cases explicitly.

---

## High-Level Request Flow

```
Client
  │
  │  POST /api/v1/code/analyze
  ▼
┌─────────────────────────────────────────────────────────┐
│  RequestIdFilter   — assigns UUID requestId → MDC       │
│  RateLimitFilter   — token bucket (Bucket4j), HTTP 429  │
│  Controller        — generated from openapi.yaml        │
│  CodeAnalysisService                                    │
│    │                                                    │
│    ├─ PromptBuilder.build()   — constructs prompt       │
│    │                                                    │
│    └─ Reactive pipeline (Mono<String>)                  │
│         │                                               │
│         ├─ CircuitBreaker     — fast-fail if open       │
│         ├─ OpenAiClient       — POST to OpenAI API      │
│         ├─ LlmResponseValidator — validates JSON        │
│         └─ (retry once on validation failure)           │
│                                                         │
│  GlobalExceptionHandler — maps all exceptions → HTTP    │
└─────────────────────────────────────────────────────────┘
  │
  │  HTTP 200 { summary, issues, improvements }
  ▼
Client
```

---

## Component Responsibilities

### `RequestIdFilter`
Runs first on every request. Reads `X-Request-Id` from the inbound header (or generates a UUID v4) and stores it in SLF4J MDC. Every log line for the duration of the request carries this ID. Echoes the value back in the response `X-Request-Id` header.

### `RateLimitFilter`
Runs second. Checks a global in-memory token bucket (Bucket4j). If the bucket is empty, rejects the request immediately with HTTP 429 and `Retry-After: 1`. Default: 10 req/s steady-state, burst of 20.

### `CodeAnalysisController`
Thin layer. Implements the interface generated from `openapi.yaml`. Delegates entirely to `CodeAnalysisService`. Contains no business logic.

### `PromptBuilder`
Constructs a deterministic prompt from the request. The JSON schema example in the prompt is derived at startup by introspecting the generated `AnalysisResponse` DTO — so `openapi.yaml` is the single source of truth for the schema. User code is wrapped between `--- BEGIN CODE ---` / `--- END CODE ---` delimiters to reduce prompt injection risk.

### `CodeAnalysisService`
Orchestrates the pipeline. Key behaviours:
- Applies a **service-level deadline** via `Mono.timeout()` — cancels the underlying HTTP request on expiry (no connection leaks)
- **Retries once** on validation failure; never retries on timeout or network errors
- Offloads JSON validation to `Schedulers.boundedElastic()` to avoid blocking the Netty event loop
- Records Micrometer metrics on every call (latency, request count, retry count, error type)

### `OpenAiClient`
Fully reactive WebClient pipeline. Sends `response_format.type = "json_schema"` with the full `AnalysisResponse` schema on every request — this is OpenAI's strict structured output mode, which constrains the model to return JSON matching the schema exactly. Handles refusals, auth errors, rate limits, and timeouts as distinct typed exceptions.

### `AnalysisResponseSchemaFactory`
Builds the `response_format.json_schema` block by introspecting `AnalysisResponse.class` using Jackson's `BeanDescription` API. The schema is serialized to a JSON string once at startup and cached — no per-request node-tree construction.

### `LlmResponseValidator`
Defensive validation layer. Even with `json_schema` strict mode, providers can return malformed payloads or change behaviour across API versions. The validator:
1. Tries a direct JSON parse (fast path — expected with `json_schema`)
2. Falls back to fence-stripping + outermost-object extraction (defensive path)
3. Deserializes into `AnalysisResponse` and runs Bean Validation (`@NotNull`, `@Size` from `openapi.yaml`)

### `GlobalExceptionHandler`
`@RestControllerAdvice` that maps every exception type to a structured `ErrorResponse` with `error`, `message`, and `requestId`. The `requestId` comes from MDC, so it always matches the `X-Request-Id` header.

### `LlmCircuitBreakerConfig`
Resilience4j circuit breaker wrapping all `LlmClient.call()` invocations. Opens after 50% failure rate over 10 calls, stays open for 30 seconds, then probes with 3 test calls. Auth errors and rate limit errors are excluded from the failure count (they are not transient).

### `LlmMetrics`
Centralises all Micrometer meter definitions. All meters for the default model are pre-registered at startup to avoid `ConcurrentHashMap` insertion on the hot path.

---

## Package Structure

```
com.aidevassistant/
├── controller/     ← Generated from openapi.yaml — do not hand-author
├── dto/            ← Generated from openapi.yaml — do not hand-author
├── service/        ← CodeAnalysisService (pipeline orchestration)
├── llm/            ← LlmClient interface, OpenAiClient, AnalysisResponseSchemaFactory, TokenUsage
├── prompt/         ← PromptBuilder
├── validator/      ← LlmResponseValidator
├── config/         ← LlmProperties, WebClientConfig, AsyncConfig,
│                      LlmMetrics, LlmCircuitBreakerConfig, RateLimitProperties
├── exception/      ← Exception hierarchy + GlobalExceptionHandler
└── util/           ← RequestIdFilter, RateLimitFilter, MdcTaskDecorator
```

---

## OpenAPI as Single Source of Truth

`openapi.yaml` drives the entire contract:

```
openapi.yaml
    │
    ├── openapi-generator-maven-plugin
    │       │
    │       ├── AnalysisRequest.java      ← with @NotNull, @Size constraints
    │       ├── AnalysisResponse.java     ← with @NotNull, @Size constraints
    │       ├── ErrorResponse.java
    │       └── ApiApi.java               ← controller interface
    │
    ├── AnalysisResponseSchemaFactory     ← introspects AnalysisResponse.class
    │       └── response_format.json_schema for OpenAI
    │
    ├── PromptBuilder                     ← derives JSON example from AnalysisResponse.class
    │       └── schema example in prompt text
    │
    └── LlmResponseValidator              ← deserializes into AnalysisResponse + Bean Validation
            └── @NotNull, @Size from generated DTO
```

Adding a field to `openapi.yaml` automatically propagates to the OpenAI schema, the prompt example, and the validator — no manual changes needed in those three places.

---

## Resilience Layers

The system has four independent resilience mechanisms, applied in order:

| Layer | Mechanism | Protects against |
|---|---|---|
| 1. Rate limiter | Bucket4j token bucket | Runaway client traffic, budget exhaustion |
| 2. Prompt size limit | `PromptTooLargeException` | Oversized requests consuming token budget |
| 3. Circuit breaker | Resilience4j | Cascading failures when OpenAI is down |
| 4. Retry | One retry on validation failure | Transient LLM formatting issues |
| 5. Service deadline | `Mono.timeout()` | Slow LLM responses holding threads |
| 6. HTTP client timeout | WebClient `.timeout()` | Network-level hangs |

---

## Threading Model

The application uses Spring MVC (Tomcat) for the HTTP layer and Spring WebFlux (Reactor Netty) for the OpenAI HTTP client only. This is intentional — the servlet stack is simpler and more familiar, while WebFlux gives us reactive timeout and cancellation semantics for the outbound LLM call.

```
Tomcat thread (handles HTTP request)
    │
    └── CodeAnalysisService.analyze()
            │
            └── Reactor Netty thread (handles OpenAI HTTP call)
                    │
                    └── boundedElastic thread (handles JSON validation)
```

The `requestId` is propagated across all three thread boundaries via MDC.

---

## Future: RAG Layer

The architecture is designed to accommodate a RAG (Retrieval-Augmented Generation) layer without changing the core pipeline:

```
Current:  PromptBuilder → LlmClient → Validator
Future:   PromptBuilder → RagRetriever → PromptEnricher → LlmClient → Validator
```

`LlmClient` and `LlmResponseValidator` are unchanged. Spring AI (compatible with Spring Boot 4) provides `VectorStore` and `EmbeddingModel` abstractions for this.
