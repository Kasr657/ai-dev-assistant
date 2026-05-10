# ADR 001 — Reactive Pipeline for LLM Calls

**Status:** Accepted  
**Date:** 2026-05

---

## Context

The application calls the OpenAI API for every request. OpenAI calls are slow (2–10 seconds) and can time out. We need a way to:
1. Cancel the underlying HTTP connection when a deadline is exceeded (not just abandon a blocked thread)
2. Avoid holding Tomcat threads during the long LLM wait
3. Apply timeout semantics that actually stop the network call

## Decision

Use Spring WebFlux `WebClient` (Reactor Netty) for the outbound OpenAI call, returning `Mono<String>` from `LlmClient.call()`. Apply `Mono.timeout()` at the service level.

The inbound HTTP layer remains Spring MVC (Tomcat) — not fully reactive. This is a deliberate hybrid: the servlet stack is simpler and more familiar for the request-handling layer, while WebFlux gives us reactive cancellation semantics for the outbound call.

## Consequences

**Good:**
- `Mono.timeout()` cancels the underlying HTTP request when the deadline fires — no connection leaks
- The service-level deadline is independent of the HTTP client timeout, giving two layers of protection
- JSON validation is offloaded to `Schedulers.boundedElastic()` to avoid blocking the Netty event loop

**Trade-offs:**
- The `.block()` call at the service boundary means the reactive pipeline provides no throughput benefit for the inbound layer — Tomcat threads are still held during the call
- Mixing servlet and reactive requires careful MDC propagation across thread boundaries

## Alternatives Considered

**Fully reactive (WebFlux end-to-end):** Would eliminate `.block()` and allow true non-blocking throughput. Rejected because it requires migrating the entire application to reactive, which adds significant complexity for a single-endpoint service.

**`CompletableFuture` with timeout:** Simpler, but `future.cancel(true)` does not cancel the underlying WebClient HTTP request — the connection would still be held until the server responds or the TCP connection times out.
