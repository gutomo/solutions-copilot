package com.example.copilot.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the gate's fail-behavior. Runs in the DEFAULT
 * {@code mvn test} -- no @Tag, no Spring context, no DB, no Bedrock --
 * against two committed fixtures. Together with EvalGateTest (which
 * enforces against the live target/eval-report.json under -Pgate), this
 * proves the gate keeps biting when it should AND keeps passing when it
 * should, on every single build.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalGateFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Map<String, Object> thresholds;

    @BeforeAll
    void loadThresholds() throws IOException {
        try (InputStream in = new ClassPathResource("eval-thresholds.yml").getInputStream()) {
            thresholds = new Yaml().load(in);
        }
    }

    @Test
    void greenFixturePassesGate() throws IOException {
        GateEvaluator.Outcome outcome = GateEvaluator.evaluate(loadFixture("green"), thresholds);
        assertThat(outcome.hardFailures())
                .as("green fixture must produce zero hard failures, got: %s", outcome.hardFailures())
                .isEmpty();
        assertThat(outcome.isGreen()).isTrue();
    }

    @Test
    void badFixtureFailsGateExactlyOnSeededMetrics() throws IOException {
        GateEvaluator.Outcome outcome = GateEvaluator.evaluate(loadFixture("bad"), thresholds);

        // Must fail at all (the gate's job).
        assertThat(outcome.isGreen())
                .as("bad fixture must produce hard failures")
                .isFalse();

        // Must fail on EXACTLY the three seeded metrics -- this proves the gate
        // discriminates, not that it fails on a globally-broken report.
        assertThat(outcome.hardFailures())
                .as("expected meanFaithfulness, judgeParseFailures, perItemFaithfulness all breached")
                .anyMatch(s -> s.contains("meanFaithfulness"))
                .anyMatch(s -> s.contains("judgeParseFailures"))
                .anyMatch(s -> s.contains("perItemFaithfulness"));

        // And must NOT fail on the metrics the bad fixture left clean.
        assertThat(outcome.hardFailures())
                .as("untouched metrics must not appear in failures")
                .noneMatch(s -> s.contains("meanCitationF1"))
                .noneMatch(s -> s.contains("meanRetrievalRecall"))
                .noneMatch(s -> s.contains("meanCorrectness"));

        // The per-item failure must point at the seeded item.
        assertThat(outcome.hardFailures())
                .anyMatch(s -> s.contains("perItemFaithfulness") && s.contains("fx-1-seeded-hallucination"));
    }

    private JsonNode loadFixture(String which) throws IOException {
        try (InputStream in = new ClassPathResource("eval/fixtures/report-" + which + ".json").getInputStream()) {
            return JSON.readTree(in);
        }
    }
}
