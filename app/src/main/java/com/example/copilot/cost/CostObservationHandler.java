package com.example.copilot.cost;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Fires for every chat-model {@code call(...)} -- including each internal
 * invocation inside the QA-advisor + tool-calling loop. The whole point of
 * intercepting at the observation layer is that the controller only sees the
 * LAST {@link ChatResponse} for an agentic request, so summing per-request
 * cost from the controller would silently undercount tool-calling turns.
 *
 * <p>Slice 1 is additive only: this handler records meters and (if a request
 * scope is active via {@link CostContext}) adds the call to the per-request
 * accumulator. It does not influence the model call, the response, or the
 * tool callbacks.
 */
public class CostObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    private static final Logger log = LoggerFactory.getLogger(CostObservationHandler.class);
    private static final String UNKNOWN_MODEL = "unknown";

    private final CostProperties costProps;
    private final MeterRegistry meters;

    public CostObservationHandler(CostProperties costProps, MeterRegistry meters) {
        this.costProps = costProps;
        this.meters = meters;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        ChatResponse response = context.getResponse();
        if (response == null) {
            return;
        }
        ChatResponseMetadata md = response.getMetadata();
        Usage usage = md == null ? null : md.getUsage();
        long inTok  = usage == null ? 0L : asLong(usage.getPromptTokens());
        long outTok = usage == null ? 0L : asLong(usage.getCompletionTokens());

        String modelId = modelOf(context);

        var rateOpt = costProps.rateFor(modelId);
        double callUsd = rateOpt
                .map(r -> (inTok / 1000.0) * r.inputPer1k() + (outTok / 1000.0) * r.outputPer1k())
                .orElse(0.0);

        // Meters: bounded tag cardinality (model id only -- no requestId tag).
        meters.counter("copilot.cost.calls.total",  "model", modelId).increment();
        meters.counter("copilot.cost.tokens.total", "model", modelId, "kind", "prompt").increment(inTok);
        meters.counter("copilot.cost.tokens.total", "model", modelId, "kind", "completion").increment(outTok);
        meters.counter("copilot.cost.usd.total",    "model", modelId).increment(callUsd);

        // Per-request accumulator (null when the call is outside the /api/chat filter).
        RequestCostAccumulator acc = CostContext.get();
        if (acc != null) {
            acc.addCall(modelId, inTok, outTok, callUsd);
            if (rateOpt.isEmpty() && acc.markWarned(modelId)) {
                log.warn("[cost] no rate for model={} (call billed at $0; configure copilot.cost.rates.\"{}\" to fix)",
                        modelId, modelId);
            }
        } else {
            log.trace("[cost] chat-model call outside request scope: model={} inTok={} outTok={}",
                    modelId, inTok, outTok);
        }
    }

    private static String modelOf(ChatModelObservationContext context) {
        Prompt prompt = context.getRequest();
        if (prompt == null) {
            return UNKNOWN_MODEL;
        }
        ChatOptions opts = prompt.getOptions();
        if (opts != null && opts.getModel() != null && !opts.getModel().isBlank()) {
            return opts.getModel();
        }
        return UNKNOWN_MODEL;
    }

    private static long asLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
