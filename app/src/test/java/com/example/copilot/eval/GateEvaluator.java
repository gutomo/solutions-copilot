package com.example.copilot.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure threshold-checking logic, extracted from EvalGateTest so the same
 * implementation runs against the live target/eval-report.json (under
 * {@code mvn test -Pgate}) AND against committed fixtures in a plain
 * unit test (EvalGateFixtureTest, default {@code mvn test}). No IO,
 * no Spring -- callers parse the report and load the thresholds.
 */
public final class GateEvaluator {

    public record Outcome(List<String> passes, List<String> hardFailures) {
        public boolean isGreen() {
            return hardFailures.isEmpty();
        }
    }

    private GateEvaluator() {}

    public static Outcome evaluate(JsonNode report, Map<String, Object> thresholds) {
        List<String> passes = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        JsonNode agg = report.get("aggregate");
        if (agg == null) {
            failures.add("[gate] FAIL: report has no 'aggregate' node");
            return new Outcome(passes, failures);
        }

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

        return new Outcome(passes, failures);
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
