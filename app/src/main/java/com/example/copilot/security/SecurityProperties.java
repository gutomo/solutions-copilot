package com.example.copilot.security;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 4 slice 4: API-key auth + per-key rate limiting config. Keys and limits
 * live here (NOT hardcoded); secret VALUES are injected from the environment
 * (Secrets Manager later). The {@code api-keys} map is logical-name -> secret;
 * the logical name becomes the authenticated principal, so rate limiting and
 * logs reference the name, never the secret.
 *
 * <p>A slot whose secret is blank/empty is treated as DISABLED (dropped in
 * {@link ApiKeyValidator}) so a blank presented credential can never match an
 * unconfigured slot -- the classic empty-credential bypass.
 */
@ConfigurationProperties("copilot.security")
public record SecurityProperties(Map<String, String> apiKeys, RateLimit rateLimit, Injection injection) {

    public SecurityProperties {
        apiKeys = apiKeys == null ? Map.of() : apiKeys;
        rateLimit = rateLimit == null ? new RateLimit(0, null) : rateLimit;
        injection = injection == null ? new Injection(true, Map.of()) : injection;
    }

    /**
     * Phase 4 slice 5: ingest-time prompt-injection scanner (Layer 1). The
     * {@code patterns} map is id -> regex; patterns live in config, never
     * hardcoded, and are compiled case-insensitively in
     * {@link InjectionScanner}. {@code enabled=false} disables the scanner (the
     * Layer-2 prompt isolation still applies).
     */
    public record Injection(boolean enabled, Map<String, String> patterns) {
        public Injection {
            patterns = patterns == null ? Map.of() : patterns;
        }
    }

    /**
     * Per-principal token bucket: {@code limitForPeriod} permits every
     * {@code limitRefreshPeriod}. In-memory / per-instance this slice
     * (distributed/Redis limiting is a later concern).
     */
    public record RateLimit(int limitForPeriod, Duration limitRefreshPeriod) {
        public RateLimit {
            if (limitForPeriod <= 0) {
                limitForPeriod = 60;
            }
            if (limitRefreshPeriod == null || limitRefreshPeriod.isZero() || limitRefreshPeriod.isNegative()) {
                limitRefreshPeriod = Duration.ofSeconds(60);
            }
        }
    }
}
