# =============================================================================
# AI Developer Assistant — Dockerfile
#
# Multi-stage build:
#   Stage 1 (builder): compiles and packages the application with Maven
#   Stage 2 (runtime): minimal JRE-only image, runs as non-root
#
# Build:
#   docker build -t ai-dev-assistant .
#
# Run:
#   docker run -p 8080:8080 -e LLM_OPENAI_API_KEY=sk-... ai-dev-assistant
#
# Optional overrides:
#   -e LLM_OPENAI_MODEL=gpt-4o-mini
#   -e LLM_OPENAI_MAX_TOKENS=512
#   -e LOGGING_LEVEL_COM_AIDEVASSISTANT=DEBUG
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 — Build
# Uses the official Maven image with Java 21 (Java 25 not yet in official images;
# the compiled bytecode runs fine on Java 21 for local dev purposes).
# For production, use a Java 25 base when available.
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy dependency descriptors first — Docker layer cache means dependencies are
# only re-downloaded when pom.xml changes, not on every source change.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build — skip tests here; run them separately in CI
COPY src ./src
COPY openapi.yaml .
RUN mvn package -DskipTests -q

# -----------------------------------------------------------------------------
# Stage 2 — Runtime
# Eclipse Temurin JRE-only image — smaller than JDK, no compiler tools.
# Pinned to a specific digest in production for reproducibility.
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Security: create a dedicated non-root user and group
RUN groupadd --system --gid 1001 appgroup && \
    useradd --system --uid 1001 --gid appgroup --no-create-home appuser

WORKDIR /app

# Copy only the fat JAR from the builder stage
COPY --from=builder /build/target/*.jar app.jar

# Set ownership to the non-root user
RUN chown appuser:appgroup app.jar

# Switch to non-root user — never run as root in production
USER appuser

# Expose the application port
EXPOSE 8080

# Health check — uses the Spring Boot actuator health endpoint
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# JVM flags:
#   -XX:+UseContainerSupport     — respect cgroup memory/CPU limits
#   -XX:MaxRAMPercentage=75.0    — use 75% of container memory for heap
#   -Djava.security.egd=...      — faster SecureRandom startup (important for UUID generation)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
