# ADR 003 — openapi.yaml as Single Source of Truth

**Status:** Accepted  
**Date:** 2026-05

---

## Context

The `AnalysisResponse` schema needs to be consistent across four places:
1. The HTTP response contract (what the API returns)
2. The OpenAI `response_format.json_schema` (what the LLM must return)
3. The prompt text (what the LLM is instructed to return)
4. The validator (what fields are checked before returning to the caller)

Maintaining these manually means any schema change (e.g. adding a `severity` field to `issues`) requires four separate code changes, and it's easy to miss one.

## Decision

Use `openapi.yaml` as the single source of truth. The `openapi-generator-maven-plugin` generates the `AnalysisResponse` DTO with Jakarta validation annotations (`@NotNull`, `@Size`) from the schema. All other places derive from the DTO at startup:

- **`AnalysisResponseSchemaFactory`** — uses Jackson `BeanDescription` to introspect `AnalysisResponse.class` and build the `json_schema` block
- **`PromptBuilder`** — uses the same `BeanDescription` to generate the JSON example in the prompt text
- **`LlmResponseValidator`** — deserializes into `AnalysisResponse` and runs `beanValidator.validate()` — the `@NotNull`/`@Size` constraints from the generated DTO do the validation

## Consequences

**Good:**
- Adding a field to `openapi.yaml` automatically propagates to the OpenAI schema, the prompt, and the validator
- No manual synchronisation between four places
- The HTTP contract and the LLM contract are always in sync

**Trade-offs:**
- The generated DTO must be on the classpath at startup for introspection to work — this is always true since the generator runs at compile time
- The prompt example is auto-generated and less human-readable than a hand-crafted example (e.g. all string fields get the same placeholder text)

## Alternatives Considered

**Jackson `JsonSchemaGenerator`:** Would generate a JSON Schema from the DTO class. Rejected because the generated schema format doesn't exactly match what OpenAI expects for `json_schema` strict mode (e.g. `additionalProperties: false` must be added manually).

**Hardcoded schema in all four places:** Simple but fragile. Any schema change requires four manual updates. Rejected.
