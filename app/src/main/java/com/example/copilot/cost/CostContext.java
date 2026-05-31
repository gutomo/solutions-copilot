package com.example.copilot.cost;

/**
 * Per-request accumulator holder. The blocking {@code POST /api/chat}
 * endpoint is single-threaded so a ThreadLocal is sound here. The streaming
 * endpoint is deliberately out of scope for slice 1; reactive context
 * propagation would be needed there.
 */
public final class CostContext {

    private static final ThreadLocal<RequestCostAccumulator> CURRENT = new ThreadLocal<>();

    private CostContext() {}

    public static void set(RequestCostAccumulator acc) {
        CURRENT.set(acc);
    }

    /** @return the accumulator for the current request, or null if outside a request scope. */
    public static RequestCostAccumulator get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
