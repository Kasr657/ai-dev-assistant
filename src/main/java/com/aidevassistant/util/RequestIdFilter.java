package com.aidevassistant.util;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that assigns a unique request ID to every inbound HTTP request.
 *
 * <p>If the caller supplies an {@code X-Request-Id} header, that value is reused;
 * otherwise a new UUID v4 is generated. The ID is stored in the SLF4J MDC under
 * the key {@code requestId} so that it appears in every log line for the duration
 * of the request. The same value is echoed back to the caller via the
 * {@code X-Request-Id} response header.
 *
 * <p>MDC is always cleared in a {@code finally} block to prevent context leakage
 * across thread-pool reuse.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** HTTP header name used to carry the request ID in both directions. */
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC key under which the request ID is stored. */
    static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
