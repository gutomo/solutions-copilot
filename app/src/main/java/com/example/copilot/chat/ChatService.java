package com.example.copilot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 4 slice 3: wraps the blocking chat call in a Resilience4j circuit
 * breaker with a fallback to a SECOND Bedrock model. A throttle/outage on the
 * primary model degrades to a working answer from the fallback instead of a
 * 500.
 *
 * <p><b>This must be invoked through the Spring proxy</b> -- i.e. from
 * {@code ChatController}, a different bean -- for the {@code @CircuitBreaker} /
 * {@code @Retry} AOP advice to fire. (The opposite of the tool-bean
 * self-invocation rule: here we WANT the proxy.) A self-call within this class
 * would bypass the aspects entirely.
 *
 * <p>The fallback re-issues the SAME prompt on the SAME {@link ChatClient} with
 * only the model overridden, so the QA advisor + the three tools (configured as
 * defaults on the bean) come along unchanged -- the fallback answer stays
 * grounded. Temperature and max-tokens are set explicitly from config rather
 * than left to options-merge, so the fallback can never silently reset them.
 *
 * <p>Observability composes for free: the fallback's {@code call()} emits its
 * own chat-model observation, so slice 1 cost-accounts it and slice 2 traces it
 * under the FALLBACK model id (the cost handler reads the model from the prompt
 * options). Its rate must exist in {@code copilot.cost.rates}; if it doesn't,
 * slice 1's loud per-request WARN is the safety net.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String CB_NAME = "bedrock-chat";

    private final ChatClient chatClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Tracer tracer;
    private final String fallbackModel;
    private final Double temperature;
    private final Integer maxTokens;

    // Stateless classifier, reused to report the breaker's verdict in the
    // fallback log line (see the probe in the slice-3 proof).
    private final BedrockFailurePredicate failureClassifier = new BedrockFailurePredicate();

    public ChatService(ChatClient chatClient,
                       CircuitBreakerRegistry circuitBreakerRegistry,
                       Tracer tracer,
                       @Value("${copilot.chat.fallback-model}") String fallbackModel,
                       @Value("${spring.ai.bedrock.converse.chat.options.temperature:0.3}") Double temperature,
                       @Value("${spring.ai.bedrock.converse.chat.options.max-tokens:1024}") Integer maxTokens) {
        this.chatClient = chatClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.tracer = tracer;
        this.fallbackModel = fallbackModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    /**
     * Primary path: the agentic RAG call on the configured (primary) model.
     * Identical to the pre-slice behaviour on the happy path. {@code @Retry} is
     * INNER to {@code @CircuitBreaker} (aspect order in config), so a bounded
     * retry on a transient primary fault counts as ONE breaker call after it
     * exhausts -- keeping the breaker's window math clean.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "chatFallback")
    @Retry(name = CB_NAME)
    public String chat(String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    /**
     * Fallback: same ChatClient, fallback model. Fires on BOTH a recorded
     * primary failure (after retries exhaust, breaker CLOSED/HALF_OPEN) and a
     * {@code CallNotPermittedException} (breaker OPEN -> primary never
     * attempted). A {@link Throwable} parameter catches both.
     *
     * <p>If the fallback model itself fails, the exception propagates to the
     * controller (a real outage of both models is not something this slice can
     * paper over).
     */
    String chatFallback(String message, Throwable cause) {
        logFallback(cause);
        tagSpan(cause);
        return chatClient.prompt()
                .options(BedrockChatOptions.builder()
                        .model(fallbackModel)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .build())
                .user(message)
                .call()
                .content();
    }

    /**
     * One concise line surfacing breaker state + the exact failure that drove
     * the fallback. {@code recorded} is the classifier's verdict on this cause
     * -- the probe in the proof reads it to confirm the forced failure is in
     * the recorded set (else the breaker would never open). Never throws.
     */
    private void logFallback(Throwable cause) {
        String state;
        try {
            state = circuitBreakerRegistry.circuitBreaker(CB_NAME).getState().name();
        } catch (RuntimeException e) {
            state = "UNKNOWN";
        }
        boolean recorded = cause != null && failureClassifier.test(cause);
        log.warn("[resilience] {} fallback engaged: cbState={} recorded={} servedBy={} cause={}",
                CB_NAME, state, recorded, fallbackModel, causeChain(cause));
    }

    /** Tag the active span so a fallen-back request is visible in the trace. Null-guarded; never throws. */
    private void tagSpan(Throwable cause) {
        try {
            Span span = tracer.currentSpan();
            if (span == null) {
                return;
            }
            span.tag("resilience.fallback", "true");
            span.tag("resilience.cb.name", CB_NAME);
            span.tag("resilience.cb.state", circuitBreakerRegistry.circuitBreaker(CB_NAME).getState().name());
            span.tag("resilience.fallback.model", fallbackModel);
            if (cause != null) {
                span.tag("resilience.cause", cause.getClass().getName());
            }
        } catch (RuntimeException tracingFailure) {
            log.debug("[resilience] failed to tag span; ignoring", tracingFailure);
        }
    }

    /** Render the full cause chain (class: message) -> ... so the probe sees wrapping, if any. */
    private static String causeChain(Throwable cause) {
        if (cause == null) {
            return "(none)";
        }
        List<String> chain = new ArrayList<>();
        Throwable t = cause;
        for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) {
            chain.add(t.getClass().getName() + ": " + t.getMessage());
        }
        return chain.stream().collect(Collectors.joining(" -> ", "[", "]"));
    }
}
