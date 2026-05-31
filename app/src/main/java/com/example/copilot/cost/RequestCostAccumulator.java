package com.example.copilot.cost;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mutable per-request totals + the per-call breakdown. Lives in a
 * {@link CostContext} ThreadLocal for the duration of one blocking
 * {@code POST /api/chat} request and is read by the
 * {@link CostObservationHandler} on every chat-model {@code onStop}. NOT
 * thread-safe -- the blocking endpoint is single-threaded per request.
 */
public final class RequestCostAccumulator {

    public record CallRecord(String model, long promptTokens, long completionTokens, double usd) {}

    private final String requestId;
    private final String path;
    private final List<CallRecord> perCall = new ArrayList<>();
    private final Set<String> warnedModels = new HashSet<>();
    private long promptTokens = 0;
    private long completionTokens = 0;
    private double usd = 0.0;

    public RequestCostAccumulator(String requestId, String path) {
        this.requestId = requestId;
        this.path = path;
    }

    public void addCall(String model, long inTok, long outTok, double callUsd) {
        perCall.add(new CallRecord(model, inTok, outTok, callUsd));
        promptTokens += inTok;
        completionTokens += outTok;
        usd += callUsd;
    }

    /** @return true if this is the first warn for {@code model} in this request (caller logs only on true). */
    public boolean markWarned(String model) {
        return warnedModels.add(model);
    }

    public String requestId()        { return requestId; }
    public String path()             { return path; }
    public int calls()               { return perCall.size(); }
    public long promptTokens()       { return promptTokens; }
    public long completionTokens()   { return completionTokens; }
    public double usd()              { return usd; }
    public List<CallRecord> perCall() { return perCall; }

    public String perCallString() {
        return perCall.stream()
                .map(c -> String.format(Locale.ROOT,
                        "m=%s:in=%d,out=%d,$=%.6f",
                        c.model(), c.promptTokens(), c.completionTokens(), c.usd()))
                .collect(Collectors.joining("; ", "[", "]"));
    }
}
