# ADR 002 — OpenAI json_schema Structured Output

**Status:** Accepted  
**Date:** 2026-05

---

## Context

The LLM must return a specific JSON structure (`summary`, `issues`, `improvements`). Without enforcement, the model can:
- Add markdown fences around the JSON
- Include explanatory text before or after the JSON
- Hallucinate field names
- Return partial JSON

The initial approach was prompt-only enforcement ("return ONLY JSON"). This is fragile and requires a retry mechanism to recover from formatting failures.

## Decision

Use OpenAI's `response_format.type = "json_schema"` with the full `AnalysisResponse` schema and `strict: true`. This is OpenAI's strict structured output mode — the model is constrained at the API level to produce JSON matching the declared schema exactly.

The schema is derived at startup by introspecting the generated `AnalysisResponse` DTO using Jackson's `BeanDescription` API. This makes `openapi.yaml` the single source of truth — adding a field to the schema in `openapi.yaml` automatically propagates to the OpenAI request.

## Consequences

**Good:**
- Eliminates the most common cause of validation failures (free-text responses, markdown wrapping)
- `additionalProperties: false` prevents the model from adding undeclared fields
- Schema is derived from the DTO — no manual synchronisation between `openapi.yaml` and the OpenAI request

**Trade-offs:**
- Supported only on `gpt-4o` (2024-08-06+) and `gpt-4o-mini`. Older models require falling back to `json_object` mode
- `LlmResponseValidator` is retained as a defensive layer — providers can still return malformed payloads or change behaviour across API versions

## Alternatives Considered

**`json_object` mode:** Guarantees valid JSON but does not enforce the schema structure. Fields can still be missing or have wrong types. Rejected in favour of `json_schema` strict mode.

**Function calling:** Equivalent enforcement but more verbose request format. `json_schema` is the recommended approach for structured output as of 2024.

**Prompt-only enforcement:** Unreliable. Kept as a fallback comment in `AnalysisResponseSchemaFactory` for older models.
