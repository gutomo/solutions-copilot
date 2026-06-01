package com.example.copilot.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates a presented {@code X-API-Key} against the configured keys and
 * returns the matching logical principal name, or {@code null} if there is no
 * match.
 *
 * <p>Two security-hygiene properties:
 * <ul>
 *   <li><b>Blank slots dropped at startup.</b> Any configured key whose secret
 *       is null/blank is discarded, so an empty or whitespace-only presented
 *       credential can never match an "empty slot" (the classic bypass).</li>
 *   <li><b>Constant-time comparison.</b> {@link MessageDigest#isEqual} compares
 *       the presented secret against each configured secret without early
 *       per-character exit. (A marginal concern with high-entropy keys, but it
 *       costs nothing and pre-empts the reviewer note.)</li>
 * </ul>
 *
 * <p>The presented secret is never logged here or by callers; only the
 * resolved principal name (on success) is ever surfaced.
 */
@Component
public class ApiKeyValidator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyValidator.class);

    private final List<Entry> keys;

    private record Entry(String name, byte[] secret) {}

    public ApiKeyValidator(SecurityProperties props) {
        this.keys = props.apiKeys().entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .map(e -> new Entry(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8)))
                .toList();
        if (this.keys.isEmpty()) {
            log.warn("[security] no API keys configured -- every /api/** request will be rejected (401). "
                    + "Set copilot.security.api-keys.<name> values via the environment.");
        } else {
            log.info("[security] {} API key(s) configured: {}", keys.size(),
                    keys.stream().map(Entry::name).toList());
        }
    }

    /**
     * @param presented the raw {@code X-API-Key} header value
     * @return the logical principal name for a matching key, or {@code null} if
     *         the credential is blank or matches no configured key
     */
    public String nameFor(String presented) {
        if (presented == null || presented.isBlank()) {
            return null;
        }
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        for (Entry e : keys) {
            if (MessageDigest.isEqual(candidate, e.secret())) {
                return e.name();
            }
        }
        return null;
    }

    /** @return immutable list of configured principal names (for the rate limiter to pre-size, if needed). */
    public List<String> principalNames() {
        return keys.stream().map(Entry::name).toList();
    }
}
