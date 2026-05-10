package com.aidevassistant.util;

import com.aidevassistant.config.RateLimitProperties;
import com.aidevassistant.exception.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Servlet filter that enforces a global API rate limit using a Bucket4j token bucket.
 *
 * <h3>Algorithm</h3>
 * <p>Uses a token bucket with:
 * <ul>
 *   <li>Steady-state refill: {@code requestsPerSecond} tokens per second</li>
 *   <li>Burst capacity: {@code burstCapacity} tokens (allows short bursts)</li>
 * </ul>
 * Each incoming request consumes one token. If the bucket is empty, the request
 * is rejected immediately with HTTP 429 and a {@code Retry-After: 1} header.
 *
 * <h3>Scope</h3>
 * <p>This is a <strong>global</strong> rate limit — it applies to all callers combined,
 * not per IP or per user. It is a safety valve to protect the OpenAI budget from
 * runaway traffic, not a per-tenant fairness mechanism.
 *
 * <h3>Storage</h3>
 * <p>The bucket is in-memory and resets on application restart. For multi-instance
 * deployments, replace with a distributed Bucket4j backend (Redis, Hazelcast, etc.).
 *
 * <h3>Order</h3>
 * <p>Runs at {@code Ordered.HIGHEST_PRECEDENCE + 1} — just after {@link RequestIdFilter}
 * so that rate-limited requests still get a {@code requestId} for log correlation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties props;
    private final Bucket bucket;

    public RateLimitFilter(RateLimitProperties props) {
        this.props = props;
        this.bucket = buildBucket(props);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!props.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            // Capture values before throwing — avoids repeated method calls
            String uri = request.getRequestURI();
            String requestId = response.getHeader("X-Request-Id");
            log.warn("Rate limit exceeded — rejecting request [uri={}, requestId={}]", uri, requestId);
            throw new RateLimitExceededException(
                    "Rate limit exceeded. Maximum " + props.getRequestsPerSecond()
                            + " requests/second (burst: " + props.getBurstCapacity()
                            + "). Retry after 1 second.");
        }
    }

    /**
     * Builds a token bucket with the configured steady-state refill rate and burst capacity.
     *
     * <p>The bucket uses a greedy refill strategy: tokens are added continuously at the
     * configured rate rather than in discrete intervals, which produces smoother behaviour
     * under bursty traffic.
     */
    private static Bucket buildBucket(RateLimitProperties props) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(props.getBurstCapacity())
                .refillGreedy(props.getRequestsPerSecond(), Duration.ofSeconds(1))
                .build();
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }
}
