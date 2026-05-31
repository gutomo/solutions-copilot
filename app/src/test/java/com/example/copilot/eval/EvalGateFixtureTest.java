package com.example.copilot.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
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

    /**
     * Dashboard coloring contract: the HTML card for a breached metric must
     * carry CSS class "card fail hard" so it renders red, while metrics that
     * pass carry "card pass hard". This proves the dashboard cannot drift
     * from the gate's verdict -- they both come from GateEvaluator.MetricResult.
     */
    @Test
    void htmlDashboardColorsBreachedMetricsRed(@TempDir Path tmp) throws IOException {
        JsonNode bad = loadFixture("bad");
        GateEvaluator.Outcome outcome = GateEvaluator.evaluate(bad, thresholds);

        // Synthetic Report mirroring the bad fixture's aggregate -- the
        // rendering check only needs the cards to be drawn. Item details are
        // not under test here.
        EvalReport.Aggregate agg = new EvalReport.Aggregate(
                /*totalItems*/ 4, /*judgeParseFailures*/ 7,
                /*meanFaithfulness*/ 50.0, /*meanRelevance*/ 90.0,
                /*meanCorrectness*/ 0.95, /*meanCitationF1*/ 1.00,
                /*meanRetrievalRecall*/ 1.00, /*goodCitationRate*/ 1.00);
        EvalReport.Item item = new EvalReport.Item(
                "fx-1-seeded-hallucination", "synthetic question", true, "synthetic answer",
                List.of(), List.of(), List.of(),
                5, "synthetic reason", 80, "synthetic reason",
                1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        EvalReport.Report report = new EvalReport.Report(
                OffsetDateTime.parse("2026-05-31T00:00:00Z"), "test",
                "synthetic-subject", "synthetic-judge", agg, List.of(item));

        Path htmlFile = tmp.resolve("eval-report-bad.html");
        EvalReport.writeHtml(htmlFile, report, outcome, List.of());

        String html = Files.readString(htmlFile);

        // Seeded breaches render with class="card fail hard".
        assertThat(html)
                .as("meanFaithfulness was seeded below floor; card must be FAIL")
                .contains("class=\"card fail hard\"")
                .containsPattern("data-key=\"meanFaithfulness\"[^>]*data-passed=\"false\"")
                .containsPattern("data-key=\"judgeParseFailures\"[^>]*data-passed=\"false\"")
                .containsPattern("data-key=\"perItemFaithfulness\"[^>]*data-passed=\"false\"");

        // Untouched metrics still render as PASS.
        assertThat(html)
                .containsPattern("data-key=\"meanCitationF1\"[^>]*data-passed=\"true\"")
                .containsPattern("data-key=\"meanRetrievalRecall\"[^>]*data-passed=\"true\"")
                .containsPattern("data-key=\"meanCorrectness\"[^>]*data-passed=\"true\"");

        // The header gate badge reflects FAIL too.
        assertThat(html).contains("data-key=\"gateStatus\" class=\"bad\"");
    }
}
