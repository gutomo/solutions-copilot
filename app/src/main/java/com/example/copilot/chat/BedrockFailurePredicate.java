package com.example.copilot.chat;

import java.util.List;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.InternalServerException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelErrorException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/**
 * Decides which failures of the primary Bedrock call should TRIP the
 * {@code bedrock-chat} circuit breaker. Wired via
 * {@code resilience4j.circuitbreaker.instances.bedrock-chat.record-failure-predicate}.
 *
 * <p><b>Why a predicate instead of plain {@code record-exceptions}:</b>
 * Resilience4j's {@code record-exceptions}/{@code ignore-exceptions} match the
 * THROWN type and its supertypes only -- they do NOT inspect the cause chain.
 * Spring AI's Bedrock converse path (and its own RetryTemplate) can WRAP the
 * underlying AWS SDK exception in a higher-level type, in which case a
 * class-list match against the thrown type would silently miss it and the
 * breaker would never open. This predicate walks the full cause chain, so it
 * is correct whether the SDK exception propagates raw or wrapped.
 *
 * <p><b>Record</b> (a different model / a retry can plausibly cure -- these are
 * primary-side faults): throttling, 5xx / internal server, model-not-ready,
 * model-timeout, model-error, quota-exceeded, the primary model/profile being
 * unavailable ({@link ResourceNotFoundException}), and client-side transport
 * faults ({@link SdkClientException}: connection / DNS / API-call timeout).
 *
 * <p><b>Ignore</b> (a fallback model will NOT help -- the request itself is the
 * problem): a request-CONTENT {@link ValidationException} (bad params, oversized
 * input) and {@link AccessDeniedException} (IAM / model-access configuration,
 * not a transient outage). These return {@code false} so the breaker stays
 * closed.
 *
 * <p><b>{@link ValidationException} is split by message:</b> Bedrock throws it
 * both for a malformed request (ignore) AND for an invalid / unknown / retired
 * MODEL IDENTIFIER ("The provided model identifier is invalid."). The latter is
 * recorded -- if the configured primary model id goes stale (a model is
 * decommissioned), failing over to a known-good second model is exactly the
 * cure. {@link #isModelIdentifierProblem(String)} draws the line.
 *
 * <p>Anything unrecognised also returns {@code false} (conservative: don't open
 * the breaker on a fault we can't classify as fallback-curable). Resilience4j
 * treats a non-recorded exception as a success for the window; the call still
 * propagates.
 *
 * <p>Resilience4j instantiates this directly (public no-arg constructor); it is
 * NOT a Spring bean.
 */
public class BedrockFailurePredicate implements Predicate<Throwable> {

    private static final Logger log = LoggerFactory.getLogger(BedrockFailurePredicate.class);

    /** Failures a fallback model (or a retry) can plausibly cure. */
    private static final List<Class<? extends Throwable>> RECORD = List.of(
            ThrottlingException.class,
            InternalServerException.class,
            ServiceQuotaExceededException.class,
            ModelNotReadyException.class,
            ModelTimeoutException.class,
            ModelErrorException.class,
            ResourceNotFoundException.class,   // primary model/profile unavailable
            SdkClientException.class);         // connection / DNS / transport / API-call timeout

    /**
     * Failures a different model will NOT fix -- the request itself is wrong.
     * {@link ValidationException} is NOT listed here: it is handled specially in
     * {@link #test(Throwable)} because an invalid-MODEL-identifier
     * ValidationException IS fallback-curable.
     */
    private static final List<Class<? extends Throwable>> IGNORE = List.of(
            AccessDeniedException.class);

    // Phrases in a ValidationException message that mark it as a model/profile
    // availability problem (record) rather than a request-content problem
    // (ignore). Matched case-insensitively.
    private static final List<String> MODEL_ID_MARKERS = List.of(
            "model identifier", "inference profile", "provided model", "model id");

    // Bound the walk so a pathological self-referential cause chain can't spin.
    private static final int MAX_DEPTH = 20;

    @Override
    public boolean test(Throwable thrown) {
        Throwable t = thrown;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++, t = t.getCause()) {
            // ValidationException is decided by its message: a model-identifier
            // problem is fallback-curable (record), a request-content problem is
            // not (ignore). Decided here, before the blanket IGNORE check.
            if (t instanceof ValidationException) {
                boolean modelProblem = isModelIdentifierProblem(t.getMessage());
                log.debug("[resilience] ValidationException thrown={} modelIdentifierProblem={} msg={}",
                        thrown.getClass().getName(), modelProblem, t.getMessage());
                return modelProblem;
            }
            if (matches(t, IGNORE)) {
                return false;
            }
            if (matches(t, RECORD)) {
                log.debug("[resilience] recording breaker failure: thrown={} matchedCause={}",
                        thrown.getClass().getName(), t.getClass().getName());
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Throwable t, List<Class<? extends Throwable>> types) {
        for (Class<? extends Throwable> type : types) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if a {@link ValidationException} message indicates the MODEL
     * identifier / inference profile is the problem (fallback-curable), false
     * for a request-content validation error or a null message (ignore).
     */
    private static boolean isModelIdentifierProblem(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return MODEL_ID_MARKERS.stream().anyMatch(lower::contains);
    }
}
