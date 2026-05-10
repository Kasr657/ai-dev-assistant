# AI Developer Assistant

A production-grade, backend-only REST API that analyzes code snippets using OpenAI and returns structured, deterministic insights.

> **This is not a chatbot.** It is a single-turn analysis pipeline where the LLM is treated as an untrusted external component — every response is validated before being returned.

---

## What it does

Send a code snippet and a programming language. Get back:

```json
{
  "summary": "Simple Hello World program with no error handling.",
  "issues": ["No error handling", "Magic string usage"],
  "improvements": ["Add error handling", "Extract string to a constant"]
}
```

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 25 |
| Maven | 3.9+ |
| OpenAI API key | [Get one here](https://platform.openai.com/api-keys) |
| Docker (optional) | 20+ |

---

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/koreddy/ai-dev-assistant.git
cd ai-dev-assistant
```

### 2. Set your OpenAI API key

```bash
export LLM_OPENAI_API_KEY=sk-...
```

### 3. Run

```bash
mvn spring-boot:run
```

The application starts on `http://localhost:8080`.

### 4. Analyze some code

```bash
curl -X POST http://localhost:8080/api/v1/code/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "code": "public class Hello { public static void main(String[] args) { System.out.println(\"Hello World\"); } }",
    "language": "Java"
  }'
```

---

## Running with Docker

### Option A — Dockerfile

```bash
# Build
docker build -t ai-dev-assistant .

# Run
docker run -p 8080:8080 \
  -e LLM_OPENAI_API_KEY=sk-... \
  ai-dev-assistant
```

### Option B — Jib (no Dockerfile needed)

```bash
# Build image to local Docker daemon
mvn jib:dockerBuild

# Run
docker run -p 8080:8080 \
  -e LLM_OPENAI_API_KEY=sk-... \
  ai-dev-assistant:latest
```

---

## API Reference

### `POST /api/v1/code/analyze`

**Request**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `code` | string | Yes | 1–50,000 characters |
| `language` | string | Yes | 1–100 characters |

**Optional header:** `X-Request-Id: <your-trace-id>` — echoed back in the response for log correlation. If omitted, a UUID is generated automatically.

**Example request**

```bash
curl -X POST http://localhost:8080/api/v1/code/analyze \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: my-trace-123" \
  -d '{
    "code": "def add(a, b):\n    return a + b",
    "language": "Python"
  }'
```

**Example response (HTTP 200)**

```json
{
  "summary": "Simple addition function with no input validation.",
  "issues": ["No type hints", "No input validation"],
  "improvements": ["Add type hints", "Validate inputs are numeric"]
}
```

**Error response (all error codes)**

```json
{
  "error": "LlmTimeoutException",
  "message": "Service-level deadline of 70s exceeded",
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**HTTP status codes**

| Code | Meaning |
|---|---|
| `200` | Success |
| `400` | Invalid request — blank field or code snippet too large |
| `422` | LLM refused to respond (content policy) |
| `429` | Rate limit exceeded — global limit or OpenAI quota |
| `500` | Internal error — check logs with the `requestId` |
| `502` | LLM returned invalid response after retry |
| `503` | Circuit breaker open — OpenAI is having issues, retry in ~30s |
| `504` | Timeout — LLM did not respond in time |

---

## Configuration

All settings can be overridden via environment variables. No code changes needed.

### LLM / OpenAI

| ENV Variable | Default | Description |
|---|---|---|
| `LLM_OPENAI_API_KEY` | *(required)* | Your OpenAI API key |
| `LLM_OPENAI_MODEL` | `gpt-4o` | Model to use (`gpt-4o`, `gpt-4o-mini`, etc.) |
| `LLM_OPENAI_MAX_TOKENS` | `1024` | Max tokens in the LLM response |
| `LLM_OPENAI_TIMEOUT_SECONDS` | `30` | HTTP timeout per OpenAI call |
| `LLM_OPENAI_SERVICE_TIMEOUT_SECONDS` | `70` | Total pipeline deadline (must be > `TIMEOUT * 2`) |
| `LLM_OPENAI_MAX_PROMPT_CHARS` | `12000` | Max code size (~3,000 tokens). Larger requests → HTTP 400 |

### Rate Limiting

| ENV Variable | Default | Description |
|---|---|---|
| `RATE_LIMIT_ENABLED` | `true` | Set to `false` to disable (useful for testing) |
| `RATE_LIMIT_REQUESTS_PER_SECOND` | `10` | Steady-state request rate |
| `RATE_LIMIT_BURST_CAPACITY` | `20` | Max burst before throttling kicks in |

### Logging

| ENV Variable | Default | Description |
|---|---|---|
| `LOGGING_LEVEL_COM_AIDEVASSISTANT` | `INFO` | Set to `DEBUG` to see sanitized prompts and LLM responses |
| `LOGGING_LEVEL_ROOT` | `INFO` | Root log level |

### Example: run with custom settings

```bash
docker run -p 8080:8080 \
  -e LLM_OPENAI_API_KEY=sk-... \
  -e LLM_OPENAI_MODEL=gpt-4o-mini \
  -e LLM_OPENAI_MAX_TOKENS=512 \
  -e RATE_LIMIT_REQUESTS_PER_SECOND=5 \
  -e LOGGING_LEVEL_COM_AIDEVASSISTANT=DEBUG \
  ai-dev-assistant
```

---

## Observability

### Health check

```bash
curl http://localhost:8080/actuator/health
```

### Prometheus metrics

```bash
curl http://localhost:8080/actuator/prometheus | grep llm_
```

Key metrics:

| Metric | What it tells you |
|---|---|
| `llm_analysis_requests_total` | Total requests by `status` (success/failure) and `model` |
| `llm_analysis_latency_seconds` | End-to-end latency with p50/p95/p99 percentiles |
| `llm_retries_total` | How often the LLM needed a retry |
| `llm_errors_total` | Errors by type (timeout, validation, circuit_open, etc.) |
| `llm_tokens_total_sum` | Total tokens consumed (cost tracking) |
| `resilience4j_circuitbreaker_state` | Circuit breaker state (0=closed, 1=open, 2=half-open) |

### Log correlation

Every request gets a `requestId`. Use it to filter logs for a specific request:

```bash
# If you supplied X-Request-Id: my-trace-123
grep "my-trace-123" application.log

# Or filter by the generated UUID from the response header
grep "550e8400-e29b-41d4-a716-446655440000" application.log
```

Log format:
```
2026-05-10 14:22:37.123 [http-nio-8080-exec-1] [my-trace-123] [12345] INFO::CodeAnalysisService Parsed AnalysisResponse: ...
```

---

## Running Tests

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=OpenAiClientTest

# Skip tests (build only)
mvn package -DskipTests
```

### Smoke test against real OpenAI

The `OpenAiSmokeTest` is disabled by default. To run it manually:

```bash
export LLM_OPENAI_API_KEY=sk-...
mvn test -Dtest=OpenAiSmokeTest
```

> Remove the `@Disabled` annotation from the test class before running, then restore it before committing.

---

## Project Structure

```
src/main/java/com/aidevassistant/
├── controller/     ← Generated from openapi.yaml
├── service/        ← Analysis pipeline orchestration
├── llm/            ← OpenAI client + schema factory
├── prompt/         ← Prompt construction
├── validator/      ← LLM response validation
├── config/         ← Properties, WebClient, metrics, circuit breaker, rate limiter
├── exception/      ← Exception hierarchy + global handler
└── util/           ← Request ID filter, rate limit filter, MDC decorator
```

The API contract (`openapi.yaml`) is the single source of truth — DTOs and controller interfaces are generated from it at build time. Do not hand-author classes in `dto/`.

---

## Troubleshooting

**App fails to start with "api-key must not be blank"**
→ Set `LLM_OPENAI_API_KEY` before starting.

**App fails to start with "service-timeout-seconds must be greater than timeout-seconds * 2"**
→ Increase `LLM_OPENAI_SERVICE_TIMEOUT_SECONDS` or decrease `LLM_OPENAI_TIMEOUT_SECONDS`.

**HTTP 429 from the API**
→ You've hit the global rate limit. Reduce request frequency or increase `RATE_LIMIT_REQUESTS_PER_SECOND`.

**HTTP 503 from the API**
→ The circuit breaker has opened due to repeated OpenAI failures. Wait ~30 seconds and retry. Check `/actuator/health` for circuit breaker state.

**HTTP 400 with "Prompt length exceeds maximum"**
→ Your code snippet is too large. Reduce it or increase `LLM_OPENAI_MAX_PROMPT_CHARS`.

**Responses are slow**
→ GPT-4o typically takes 2–5 seconds. Try `gpt-4o-mini` for faster (but less detailed) analysis.

**Want to see what prompt is being sent?**
→ Set `LOGGING_LEVEL_COM_AIDEVASSISTANT=DEBUG`. The prompt is logged with code content replaced by `[REDACTED]`.

---

## Future Enhancements

- **RAG (Retrieval-Augmented Generation)** — enrich prompts with your codebase's own patterns, style guides, and architecture decisions using Spring AI + a vector store
- **`confidence` field** — add a self-reported or heuristic confidence score to the response

---

## Further Reading

| Document | What's in it |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | System design, component responsibilities, threading model |
| [`docs/dependencies.md`](docs/dependencies.md) | Every dependency explained — what it is and why it's here |
| [`docs/observability.md`](docs/observability.md) | Metrics reference, log format, Prometheus/Grafana setup |
| [`docs/development.md`](docs/development.md) | Build, test, extend — everything for contributors |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records — why key decisions were made |
| [`agents.MD`](agents.MD) | Architecture overview for AI coding agents |
