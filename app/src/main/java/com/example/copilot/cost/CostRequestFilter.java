package com.example.copilot.cost;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

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
 */
public class CostRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CostRequestFilter.class);
    private static final String TARGET_PATH = "/api/chat";

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
}
