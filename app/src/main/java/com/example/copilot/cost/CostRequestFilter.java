package com.example.copilot.cost;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the per-request cost scope for blocking {@code POST /api/chat}
 * requests and emits the {@code [cost]} INFO line in a {@code finally} block
 * once the controller has returned. Headers are intentionally NOT set here:
 * the @RestController commits the response during {@code doFilter}, so a
 * {@code response.setHeader(...)} after returning is silently dropped once
 * the body exceeds the container's output buffer.
 *
 * <p>SSE {@code GET /api/chat/stream} is deliberately skipped (reactive flow
 * needs context propagation; deferred to a later slice).
 *
 * <p>Slice 2: at filter-exit (before the {@code finally} closes the HTTP
 * span) we tag the current span with the request's accumulated cost so
 * traces show both structure AND $. The HTTP root span is still active here
 * because this filter is registered without explicit ordering -- Boot's
 * default precedence places custom filters INSIDE Boot's
 * {@code ServerHttpObservationFilter} scope. {@link Tracer#currentSpan()}
 * is {@code @Nullable} (returns null when no observation is active, e.g.
 * sampling=0 default or paths outside the observation filter); every
 * dereference is null-guarded so a missing span never breaks the response.
 */
public class CostRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CostRequestFilter.class);
    private static final String TARGET_PATH = "/api/chat";

    private final Tracer tracer;

    public CostRequestFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Exact match: skip /api/chat/stream and any sub-path.
        return !TARGET_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        RequestCostAccumulator acc = new RequestCostAccumulator(requestId, request.getRequestURI());
        CostContext.set(acc);
        try {
            chain.doFilter(request, response);
        } finally {
            tagCurrentSpan(acc);
            log.info("[cost] requestId={} path={} calls={} promptTokens={} completionTokens={} usd={} perCall={}",
                    acc.requestId(),
                    acc.path(),
                    acc.calls(),
                    acc.promptTokens(),
                    acc.completionTokens(),
                    String.format(Locale.ROOT, "%.6f", acc.usd()),
                    acc.perCallString());
            CostContext.clear();
        }
    }

    /**
     * Attach the request's accumulated cost / token / call counts to the
     * HTTP root span as attributes. Null-guarded: {@code currentSpan()} can
     * return null (sampling=0 default, paths outside the observation
     * filter, tracing absent from the runtime). Tagging a non-recording
     * span is a documented no-op, so it's safe to tag whenever the span is
     * non-null. Never throws -- a tracing failure here MUST NOT disrupt the
     * response, same principle as the no-collector graceful-degrade.
     */
    private void tagCurrentSpan(RequestCostAccumulator acc) {
        try {
            Span span = tracer.currentSpan();
            if (span == null) {
                return;
            }
            span.tag("ai.cost.usd",      String.format(Locale.ROOT, "%.6f", acc.usd()));
            span.tag("ai.chat.calls",    Integer.toString(acc.calls()));
            span.tag("ai.tokens.input",  Long.toString(acc.promptTokens()));
            span.tag("ai.tokens.output", Long.toString(acc.completionTokens()));
        } catch (RuntimeException tracingFailure) {
            // Belt-and-braces. The tag API on a non-recording span is a
            // no-op, but if some future bridge throws here, swallow it --
            // an instrumentation failure must not break /api/chat.
            log.debug("[cost] failed to tag current span; ignoring", tracingFailure);
        }
    }
}
