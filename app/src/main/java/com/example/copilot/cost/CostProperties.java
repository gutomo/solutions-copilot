package com.example.copilot.cost;

import java.util.Map;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-model token pricing, sourced from application.yml (NOT hardcoded). Map
 * key is the Bedrock model id / inference-profile id (e.g.
 * {@code jp.anthropic.claude-haiku-4-5-20251001-v1:0}). YAML keys with dots
 * must use the bracket-escape form: {@code "[jp.anthropic...]"}.
 *
 * <p>Unknown-model behaviour is in {@link CostObservationHandler}: a missing
 * rate logs WARN once per request and contributes $0 (tokens are still
 * counted, so the call shows up).
 */
@ConfigurationProperties("copilot.cost")
public record CostProperties(Map<String, ModelRate> rates) {

    public CostProperties {
        if (rates == null) {
            rates = Map.of();
        }
    }

    /**
     * @param inputPer1k  USD per 1000 prompt tokens
     * @param outputPer1k USD per 1000 completion tokens
     */
    public record ModelRate(double inputPer1k, double outputPer1k) {}

    public Optional<ModelRate> rateFor(String modelId) {
        return Optional.ofNullable(rates.get(modelId));
    }
}
