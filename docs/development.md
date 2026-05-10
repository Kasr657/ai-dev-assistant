# Development Guide

Everything you need to build, test, and extend this service.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 25 | `java --version` to check |
| Maven | 3.9+ | `mvn --version` to check |
| Docker | 20+ | Optional — for container builds |
| OpenAI API key | — | Required to run the app |

---

## Building

```bash
# Compile only
mvn compile

# Run all tests
mvn test

# Package (fat JAR)
mvn package

# Package without tests
mvn package -DskipTests

# Full build + verify
mvn verify
```

The `openapi-generator-maven-plugin` runs automatically during `compile` and generates DTOs and the controller interface from `openapi.yaml` into `target/generated-sources/openapi/`.

---

## Running Locally

```bash
export LLM_OPENAI_API_KEY=sk-...
mvn spring-boot:run
```

Or with additional overrides:

```bash
LLM_OPENAI_API_KEY=sk-... \
LLM_OPENAI_MODEL=gpt-4o-mini \
LOGGING_LEVEL_COM_AIDEVASSISTANT=DEBUG \
mvn spring-boot:run
```

---

## Testing

### Run all tests

```bash
mvn test
```

### Run a specific test class

```bash
mvn test -Dtest=OpenAiClientTest
mvn test -Dtest=CodeAnalysisServiceTest
mvn test -Dtest=LlmMetricsTest
```

### Run a specific test method

```bash
mvn test -Dtest=OpenAiClientTest#call_throwsLlmAuthException_on401
```

### Test types

| Type | Location | What it tests |
|---|---|---|
| Unit | `src/test/java/.../service/`, `prompt/`, `validator/`, `config/`, `util/` | Individual components with mocks |
| Property-based | `PromptBuilderTest`, `LlmResponseValidatorTest` | Universal correctness properties (jqwik, 50 tries each) |
| HTTP client | `llm/OpenAiClientTest` | Real HTTP interactions via MockWebServer |
| Integration | `integration/CodeAnalysisIntegrationTest`, `GoldenTest` | Full Spring context + MockMvc |
| Startup | `integration/ApplicationContextTest` | Context loads / fails correctly |
| Smoke | `llm/OpenAiSmokeTest` | Real OpenAI API call (`@Disabled`) |

### Running the smoke test

The smoke test makes a real call to OpenAI. It is `@Disabled` by default.

```bash
# 1. Remove @Disabled from OpenAiSmokeTest.java temporarily
# 2. Run
export LLM_OPENAI_API_KEY=sk-...
mvn test -Dtest=OpenAiSmokeTest
# 3. Restore @Disabled before committing
```

---

## Project Layout

```
ai-dev-assistant/
├── src/
│   ├── main/
│   │   ├── java/com/aidevassistant/   ← Application source
│   │   └── resources/
│   │       └── application.properties ← All configuration
│   └── test/
│       └── java/com/aidevassistant/   ← All tests
├── docs/                              ← This directory
├── openapi.yaml                       ← API contract (single source of truth)
├── Dockerfile                         ← Multi-stage container build
├── .dockerignore
├── .gitignore
├── agents.MD                          ← Architecture overview for AI agents
├── README.md                          ← Quick start guide
└── pom.xml
```

### Generated code

The `target/generated-sources/openapi/` directory is created at build time and contains:
- `com.aidevassistant.dto.AnalysisRequest`
- `com.aidevassistant.dto.AnalysisResponse`
- `com.aidevassistant.dto.ErrorResponse`
- `com.aidevassistant.controller.ApiApi` (controller interface)

**Do not hand-author classes in `src/main/java/com/aidevassistant/dto/` or duplicate the `ApiApi` interface.** All changes to the API contract go in `openapi.yaml`.

---

## Adding a New Feature

### Checklist

1. **Update `openapi.yaml`** if the feature changes the API contract (new fields, new endpoints, new response codes)
2. **Run `mvn compile`** to regenerate DTOs after changing `openapi.yaml`
3. **Implement** the feature in the appropriate package
4. **Write tests** — unit tests for new components, integration tests for new HTTP behaviour
5. **Update documentation** — see the checklist in `agents.MD`

### Adding a new endpoint

1. Add the path and schemas to `openapi.yaml`
2. Run `mvn compile` — the generator creates the new interface method
3. Implement the method in `CodeAnalysisController` (or create a new controller)
4. Add a service method and wire up the pipeline
5. Add integration tests

### Adding a new configuration property

1. Add the field to `LlmProperties` (or `RateLimitProperties`) with `@Min`/`@NotBlank` as appropriate
2. Add the property to `application.properties` with an ENV variable override and a comment
3. Document it in `agents.MD` configuration table and `README.md`

### Adding a new exception

1. Create the exception class in `com.aidevassistant.exception`
2. Add an `@ExceptionHandler` method in `GlobalExceptionHandler`
3. Add it to the exception hierarchy in `agents.MD` and `docs/architecture.md`
4. Add the HTTP code to `openapi.yaml` response codes

---

## Code Style

- **No hand-authored DTOs** — all request/response types come from `openapi.yaml`
- **Constructor injection** — no `@Autowired` on fields
- **Reactive for outbound, servlet for inbound** — `WebClient` for OpenAI calls, Tomcat for HTTP
- **Fail fast** — validate at startup (`@PostConstruct`), reject early (`PromptTooLargeException`)
- **Log with context** — always use MDC `requestId` in error logs
- **Test behaviour, not implementation** — mock at the `LlmClient` boundary in integration tests

---

## Common Issues

**`mvn compile` fails with "cannot find symbol" on generated classes**
→ The generated sources directory may not be on the compile path. Run `mvn generate-sources` first, or check that `target/generated-sources/openapi` is marked as a source root in your IDE.

**Tests fail with "LLM_OPENAI_API_KEY must not be blank"**
→ Integration tests set `llm.openai.api-key=test-key` via `@SpringBootTest(properties = ...)`. If you're running a test that doesn't do this, add the property.

**`@MockitoBean` not found**
→ This annotation is in `spring-boot-starter-webmvc-test` (Spring Boot 4). Make sure that dependency is in `pom.xml`.

**`@AutoConfigureMockMvc` import error**
→ In Spring Boot 4, import from `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, not the old `org.springframework.boot.test.autoconfigure.web.servlet` package.
