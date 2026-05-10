# ADR 004 — Circuit Breaker Excludes Auth and Rate Limit Errors

**Status:** Accepted  
**Date:** 2026-05

---

## Context

The Resilience4j circuit breaker opens when the failure rate exceeds 50% over a 10-call sliding window. The question is: which exceptions should count as failures?

The system has several exception types that map to different root causes:
- `LlmTimeoutException` — OpenAI is slow or unavailable (transient)
- `LlmCommunicationException` — network error (transient)
- `LlmValidationException` / `LlmResponseFailureException` — LLM returned bad JSON (transient)
- `LlmAuthException` — API key is invalid (configuration error, not transient)
- `LlmRateLimitException` — rate limit exceeded (expected under load, not a sign of OpenAI being down)

## Decision

Exclude `LlmAuthException` and `LlmRateLimitException` from the circuit breaker failure count.

```java
.ignoreExceptions(LlmAuthException.class, LlmRateLimitException.class)
```

## Reasoning

**`LlmAuthException` (HTTP 401):** This is a configuration error — the API key is wrong. Opening the circuit breaker won't fix it and will make the problem worse by adding HTTP 503 responses on top of the 500s. The operator needs to fix the key, not wait for the circuit to reset.

**`LlmRateLimitException` (HTTP 429):** Rate limits are expected under load. They indicate the system is sending too many requests, not that OpenAI is down. Opening the circuit breaker would cause HTTP 503 responses when the correct response is HTTP 429 with a back-off. The rate limiter (`RateLimitFilter`) is the right mechanism for this.

## Consequences

**Good:**
- The circuit breaker only opens for genuinely transient failures (timeouts, network errors, bad responses)
- Auth errors and rate limit errors surface with their correct HTTP codes (500 and 429 respectively)
- Operators get accurate signals: 503 means "OpenAI is having issues", not "your key is wrong"

**Trade-offs:**
- A sustained rate limit storm won't trip the circuit breaker — but the global rate limiter should prevent this from reaching OpenAI in the first place
