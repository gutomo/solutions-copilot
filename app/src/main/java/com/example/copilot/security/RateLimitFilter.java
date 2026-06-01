package com.example.copilot.security;

import java.io.IOException;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-principal (per API key) rate limiting for {@code /api/**}. Wired AFTER
 * Spring Security's {@code AuthorizationFilter}, so it only ever runs for an
 * already-authenticated request -- an anonymous request is 401'd first and
 * NEVER consumes a permit. Over the limit -> {@code 429} + {@code Retry-After}.
 *
 * <p>One Resilience4j {@link RateLimiter} per principal, drawn from a registry
 * sharing one config. Keys are a bounded config set, so limiter instances are
 * bounded. In-memory / per-instance this slice; distributed (Redis) limiting
 * across replicas is a later concern.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String API_PREFIX = "/api/";

    private final RateLimiterRegistry registry;
    private final long fallbackRetryAfterSeconds;

    public RateLimitFilter(RateLimiterRegistry registry, long fallbackRetryAfterSeconds) {
        this.registry = registry;
        this.fallbackRetryAfterSeconds = fallbackRetryAfterSeconds;
    }

    // Rate-limit once per LOGICAL request, on the initial dispatch only. The SSE
    // endpoint (/api/chat/stream) re-dispatches ASYNC on completion; re-running
    // here would double-count the permit and -- worse -- a 429 written after the
    // 200 SSE response has committed throws ("response already committed") and
    // surfaces as a spurious /error 500. The permit was already taken on the
    // initial dispatch, so skipping async/error is both correct and safe.
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Only the API surface is rate-limited; authenticated actuator reads are not.
        if (!request.getRequestURI().startsWith(API_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            // Defensive: authorization should already have rejected anonymous.
            filterChain.doFilter(request, response);
            return;
        }

        RateLimiter limiter = registry.rateLimiter(auth.getName());
        if (limiter.acquirePermission()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfter = retryAfterSeconds(limiter);
        log.warn("[security] rate limit exceeded principal={} path={} retryAfter={}s",
                auth.getName(), request.getRequestURI(), retryAfter);
        response.setStatus(429); // 429 Too Many Requests
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate_limited\"}");
    }

    /**
     * Seconds until permits replenish: read from the limiter's nanos-to-wait
     * when available, else fall back to the refresh-period upper bound. Always
     * >= 1 so a client never busy-retries.
     */
    private long retryAfterSeconds(RateLimiter limiter) {
        long nanos = -1L;
        if (limiter instanceof AtomicRateLimiter atomic) {
            nanos = atomic.getDetailedMetrics().getNanosToWait();
        }
        if (nanos <= 0L) {
            return Math.max(1L, fallbackRetryAfterSeconds);
        }
        return Math.max(1L, (nanos + 999_999_999L) / 1_000_000_000L); // ceil to seconds
    }
}
