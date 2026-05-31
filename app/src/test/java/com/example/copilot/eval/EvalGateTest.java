package com.example.copilot.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.fail;

/**
 * Reads target/eval-report.json (written by `mvn test -Peval`) and
 * src/test/resources/eval-thresholds.yml, then fails the build if any HARD
 * threshold is breached. Soft thresholds are reported but never fail.
 *
 * Runs under its own profile (`mvn test -Pgate`) AFTER the eval has written
 * the report. Plain JUnit -- no Spring context -- so it's fast and decoupled
 * from corpus / Bedrock.
 */
@Tag("gate")
class EvalGateTest {

    private static final Path REPORT = Path.of("target", "eval-report.json");
    private static final String THRESHOLDS_RESOURCE = "eval-thresholds.yml";

    @Test
    void enforceThresholds() throws IOException {
        if (!Files.exists(REPORT)) {
            fail("[gate] FAIL: %s not found. Run `mvn test -Peval` first to produce the report.", REPORT);
        }

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode report = json.readTree(REPORT.toFile());
        JsonNode agg = report.get("aggregate");
        if (agg == null) {
            fail("[gate] FAIL: report has no 'aggregate' node");
        }

        Map<String, Object> thresholds;
        try (InputStream in = new ClassPathResource(THRESHOLDS_RESOURCE).getInputStream()) {
            thresholds = new Yaml().load(in);
        }

        List<String> passes = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        // ---- aggregate floors (mean*) ----
        checkFloor(thresholds, agg, "meanFaithfulness",    passes, failures);
        checkFloor(thresholds, agg, "meanCitationF1",      passes, failures);
        checkFloor(thresholds, agg, "meanRetrievalRecall", passes, failures);
        checkFloor(thresholds, agg, "meanCorrectness",     passes, failures);
        checkFloor(thresholds, agg, "meanRelevance",       passes, failures);

        // ---- aggregate ceiling (judgeParseFailures) ----
        checkCeiling(thresholds, agg, "judgeParseFailures", passes, failures);

        // ---- per-item faithfulness floor (every item >= floor) ----
        checkPerItemFloor(thresholds, report, "perItemFaithfulness", "faithfulnessScore", passes, failures);

        System.out.println();
        System.out.println("=== EVAL GATE ===");
        passes.forEach(System.out::println);
        if (!failures.isEmpty()) {
            failures.forEach(System.out::println);
            System.out.println();
            fail("[gate] FAIL: " + failures.size() + " hard threshold(s) breached");
        }
        System.out.println("[gate] PASS: all hard thresholds satisfied");
    }

    @SuppressWarnings("unchecked")
    private static void checkFloor(Map<String, Object> thresholds, JsonNode agg, String key,
                                   List<String> passes, List<String> failures) {
        Object raw = thresholds.get(key);
        if (!(raw instanceof Map)) return;
        Map<String, Object> cfg = (Map<String, Object>) raw;
        double floor = ((Number) cfg.get("floor")).doubleValue();
        boolean hard = Boolean.TRUE.equals(cfg.get("hard"));
        JsonNode node = agg.get(key);
        if (node == null) {
            failures.add(String.format("[gate] FAIL metric=%s NOT-FOUND in report.aggregate", key));
            return;
        }
        double observed = node.asDouble();
        String tag = hard ? "FAIL" : "WARN";
        if (observed >= floor) {
            passes.add(String.format("[gate] PASS metric=%-22s observed=%7.3f threshold>=%.3f", key, observed, floor));
        } else {
            String msg = String.format("[gate] %s metric=%-22s observed=%7.3f threshold>=%.3f", tag, key, observed, floor);
            if (hard) failures.add(msg); else passes.add(msg);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkCeiling(Map<String, Object> thresholds, JsonNode agg, String key,
                                     List<String> passes, List<String> failures) {
        Object raw = thresholds.get(key);
        if (!(raw instanceof Map)) return;
        Map<String, Object> cfg = (Map<String, Object>) raw;
        double ceiling = ((Number) cfg.get("ceiling")).doubleValue();
        boolean hard = Boolean.TRUE.equals(cfg.get("hard"));
        JsonNode node = agg.get(key);
        if (node == null) {
            failures.add(String.format("[gate] FAIL metric=%s NOT-FOUND in report.aggregate", key));
            return;
        }
        double observed = node.asDouble();
        String tag = hard ? "FAIL" : "WARN";
        if (observed <= ceiling) {
            passes.add(String.format("[gate] PASS metric=%-22s observed=%7.0f threshold<=%.0f", key, observed, ceiling));
        } else {
            String msg = String.format("[gate] %s metric=%-22s observed=%7.0f threshold<=%.0f", tag, key, observed, ceiling);
            if (hard) failures.add(msg); else passes.add(msg);
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkPerItemFloor(Map<String, Object> thresholds, JsonNode report, String key,
                                          String itemField, List<String> passes, List<String> failures) {
        Object raw = thresholds.get(key);
        if (!(raw instanceof Map)) return;
        Map<String, Object> cfg = (Map<String, Object>) raw;
        double floor = ((Number) cfg.get("floor")).doubleValue();
        boolean hard = Boolean.TRUE.equals(cfg.get("hard"));
        JsonNode items = report.get("items");
        if (items == null || !items.isArray()) {
            failures.add(String.format("[gate] FAIL metric=%s items[] missing in report", key));
            return;
        }
        int below = 0;
        String worstId = null;
        int worstScore = Integer.MAX_VALUE;
        for (JsonNode it : items) {
            int score = it.get(itemField).asInt();
            if (score < 0) continue; // -1 = judge parse fail, surfaced via judgeParseFailures
            if (score < floor) {
                below++;
                if (score < worstScore) {
                    worstScore = score;
                    worstId = it.get("id").asText();
                }
            }
        }
        String tag = hard ? "FAIL" : "WARN";
        if (below == 0) {
            passes.add(String.format("[gate] PASS metric=%-22s observed=%7s threshold>=%.0f (every item)", key, "all-above", floor));
        } else {
            String msg = String.format("[gate] %s metric=%-22s observed=%d-below worst=%s@%d threshold>=%.0f",
                    tag, key, below, worstId, worstScore, floor);
            if (hard) failures.add(msg); else passes.add(msg);
        }
    }
}
