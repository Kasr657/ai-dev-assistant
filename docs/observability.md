# Observability

Every request in this system is traceable, measurable, and debuggable. This document covers logging, metrics, and health endpoints.

---

## Request Tracing

Every HTTP request gets a unique `requestId` that appears in:
- Every log line for the duration of the request
- The `X-Request-Id` response header
- The `requestId` field in every error response body

### Supplying your own trace ID

```bash
curl -X POST http://localhost:8080/api/v1/code/analyze \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: my-trace-abc123" \
  -d '{"code": "int x = 1;", "language": "Java"}'
```

The response will include `X-Request-Id: my-trace-abc123`. Use this to correlate logs across your system.

### Filtering logs by request ID

```bash
# Tail logs and filter by a specific request
tail -f application.log | grep "my-trace-abc123"

# Or search historical logs
grep "my-trace-abc123" application.log
```

### Log format

```
2026-05-10 14:22:37.123 [http-nio-8080-exec-1] [my-trace-abc123] [12345] INFO::CodeAnalysisService Parsed AnalysisResponse: ...
│                        │                       │                 │       │    │                   │
│                        │                       │                 │       │    │                   └─ message
│                        │                       │                 │       │    └─ logger class (short)
│                        │                       │                 │       └─ log level
│                        │                       │                 └─ PID
│                        │                       └─ requestId (from MDC)
│                        └─ thread name
└─ timestamp (local timezone)
```

### Log levels

| Level | What you see |
|---|---|
| `INFO` (default) | Request outcomes, retries, warnings, errors |
| `DEBUG` | Sanitized prompts (`[REDACTED]` for code), truncated LLM responses (first 500 chars), parsed responses, token usage |

Enable DEBUG for the application:
```bash
export LOGGING_LEVEL_COM_AIDEVASSISTANT=DEBUG
```

> **Note:** Even at DEBUG, user code is never logged. The code block in the prompt is replaced with `[REDACTED]`.

---

## Metrics

Metrics are exposed at `/actuator/prometheus` in Prometheus text format.

```bash
curl http://localhost:8080/actuator/prometheus | grep llm_
```

### LLM Analysis Metrics

#### `llm_analysis_requests_total`
Total number of analysis requests, tagged by outcome and model.

```
llm_analysis_requests_total{application="ai-dev-assistant",model="gpt-4o",status="success"} 42.0
llm_analysis_requests_total{application="ai-dev-assistant",model="gpt-4o",status="failure"} 3.0
```

#### `llm_analysis_latency_seconds`
End-to-end latency from request received to response sent. Includes p50, p95, p99 percentiles.

```
llm_analysis_latency_seconds{application="ai-dev-assistant",model="gpt-4o",status="success",quantile="0.5"} 2.341
llm_analysis_latency_seconds{application="ai-dev-assistant",model="gpt-4o",status="success",quantile="0.95"} 4.812
llm_analysis_latency_seconds{application="ai-dev-assistant",model="gpt-4o",status="success",quantile="0.99"} 7.203
```

#### `llm_retries_total`
Number of times the LLM call was retried due to a validation failure.

```
llm_retries_total{application="ai-dev-assistant",model="gpt-4o"} 2.0
```

#### `llm_errors_total`
Errors broken down by type. Use this to understand failure patterns.

```
llm_errors_total{application="ai-dev-assistant",error_type="timeout"} 1.0
llm_errors_total{application="ai-dev-assistant",error_type="validation"} 2.0
llm_errors_total{application="ai-dev-assistant",error_type="communication"} 0.0
llm_errors_total{application="ai-dev-assistant",error_type="refusal"} 0.0
llm_errors_total{application="ai-dev-assistant",error_type="auth"} 0.0
llm_errors_total{application="ai-dev-assistant",error_type="rate_limit"} 0.0
llm_errors_total{application="ai-dev-assistant",error_type="circuit_open"} 0.0
```

### Token Usage Metrics

Track OpenAI token consumption for cost monitoring.

```
llm_tokens_prompt_sum{application="ai-dev-assistant",model="gpt-4o"} 12450.0
llm_tokens_completion_sum{application="ai-dev-assistant",model="gpt-4o"} 3820.0
llm_tokens_total_sum{application="ai-dev-assistant",model="gpt-4o"} 16270.0
```

> GPT-4o pricing: ~$2.50 per 1M input tokens, ~$10 per 1M output tokens (as of 2025). Use `llm_tokens_prompt_sum` and `llm_tokens_completion_sum` to estimate cost.

### Circuit Breaker Metrics

Published automatically by Resilience4j under `resilience4j_circuitbreaker_*`.

```
# Circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN
resilience4j_circuitbreaker_state{name="llm-openai"} 0.0

# Failure rate (0.0–1.0)
resilience4j_circuitbreaker_failure_rate{name="llm-openai"} 0.0

# Number of calls in the sliding window
resilience4j_circuitbreaker_calls_total{kind="successful",name="llm-openai"} 42.0
resilience4j_circuitbreaker_calls_total{kind="failed",name="llm-openai"} 1.0
```

### Standard Spring Boot Metrics

The actuator also exposes JVM, Tomcat, and HTTP metrics automatically:

```bash
# HTTP request metrics
curl http://localhost:8080/actuator/prometheus | grep http_server_requests

# JVM memory
curl http://localhost:8080/actuator/prometheus | grep jvm_memory

# Tomcat thread pool
curl http://localhost:8080/actuator/prometheus | grep tomcat_threads
```

---

## Health Endpoints

### `/actuator/health`

```bash
curl http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "circuitBreakers": {
      "status": "UP",
      "details": {
        "llm-openai": {
          "details": {
            "failureRate": "-1.0%",
            "failureRateThreshold": "50.0%",
            "state": "CLOSED"
          }
        }
      }
    },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

Use this for:
- **Liveness probe** — is the application running?
- **Readiness probe** — is the circuit breaker healthy?
- **Docker HEALTHCHECK** — already configured in the `Dockerfile`

### `/actuator/info`

```bash
curl http://localhost:8080/actuator/info
```

Returns application metadata (name, version).

---

## Prometheus + Grafana Setup (Optional)

To visualise metrics locally:

**1. `docker-compose.yml`**

```yaml
version: '3.8'
services:
  app:
    image: ai-dev-assistant:latest
    ports:
      - "8080:8080"
    environment:
      - LLM_OPENAI_API_KEY=${LLM_OPENAI_API_KEY}

  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./docs/prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
```

**2. `docs/prometheus.yml`**

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'ai-dev-assistant'
    static_configs:
      - targets: ['app:8080']
    metrics_path: '/actuator/prometheus'
```

**3. Run**

```bash
docker-compose up
```

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin/admin)

**Useful Prometheus queries:**

```promql
# Request rate (req/s)
rate(llm_analysis_requests_total[1m])

# p95 latency
histogram_quantile(0.95, rate(llm_analysis_latency_seconds_bucket[5m]))

# Error rate
rate(llm_errors_total[5m])

# Token consumption rate
rate(llm_tokens_total_sum[1m])

# Circuit breaker state
resilience4j_circuitbreaker_state{name="llm-openai"}
```
