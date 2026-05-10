package com.aidevassistant.util;

import com.aidevassistant.config.RateLimitProperties;
import com.aidevassistant.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimitFilter}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Requests within the rate limit pass through</li>
 *   <li>Requests exceeding the burst capacity are rejected with {@link RateLimitExceededException}</li>
 *   <li>Rate limiting can be disabled via configuration</li>
 * </ul>
 */
class RateLimitFilterTest {

    // =========================================================================
    // Requests within limit pass through
    // =========================================================================

    @Test
    void doFilter_allowsRequestsWithinBurstCapacity() {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerSecond(10);
        props.setBurstCapacity(5);

        RateLimitFilter filter = new RateLimitFilter(props);

        // Send 5 requests — all should pass (burst capacity = 5)
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/code/analyze");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            assertDoesNotThrow(() -> filter.doFilter(request, response, chain),
                    "Request " + (i + 1) + " should pass within burst capacity");
        }
    }

    // =========================================================================
    // Requests exceeding burst capacity are rejected
    // =========================================================================

    @Test
    void doFilter_throwsRateLimitExceededException_whenBucketIsEmpty() throws Exception {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(true);
        props.setRequestsPerSecond(1);
        props.setBurstCapacity(2); // only 2 tokens available

        RateLimitFilter filter = new RateLimitFilter(props);

        // Drain the bucket
        for (int i = 0; i < 2; i++) {
            filter.doFilter(
                    new MockHttpServletRequest("POST", "/api/v1/code/analyze"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }

        // Next request should be rejected
        var request = new MockHttpServletRequest("POST", "/api/v1/code/analyze");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        assertThrows(RateLimitExceededException.class,
                () -> filter.doFilter(request, response, chain),
                "Request exceeding burst capacity must throw RateLimitExceededException");
    }

    // =========================================================================
    // Rate limiting can be disabled
    // =========================================================================

    @Test
    void doFilter_passesAllRequests_whenRateLimitingIsDisabled() {
        RateLimitProperties props = new RateLimitProperties();
        props.setEnabled(false);
        props.setRequestsPerSecond(1);
        props.setBurstCapacity(1); // would reject after 1 request if enabled

        RateLimitFilter filter = new RateLimitFilter(props);

        // Send 10 requests — all should pass because rate limiting is disabled
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() ->
                    filter.doFilter(
                            new MockHttpServletRequest("POST", "/api/v1/code/analyze"),
                            new MockHttpServletResponse(),
                            new MockFilterChain()),
                    "All requests should pass when rate limiting is disabled");
        }
    }
}
