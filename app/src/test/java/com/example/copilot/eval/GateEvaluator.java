package com.example.copilot.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure threshold-checking logic. Extracted from EvalGateTest in slice 3 so the
 * same implementation runs against the live target/eval-report.json (under
 * {@code mvn test -Pgate}) AND against committed fixtures in a plain unit test
 * (EvalGateFixtureTest, default {@code mvn test}). No IO, no Spring.
 *
 * Slice 4: extended with {@link MetricResult} per-metric structured output so
 * the HTML dashboard can color cards from the exact same evaluation logic --
 * dashboard cannot drift from what the gate enforces. The existing
 * {@code passes()} / {@code hardFailures()} string lists are derived from the
 * metric results; existing callers continue to work unchanged.
 */
public final class GateEvaluator {

    public enum Direction { FLOOR, CEILING }

    /**
     * One per gated/observed metric in the report.
     *
     * @param key       JSON key in report.aggregate (or "perItemFaithfulness")
     * @param label     human-readable label for the dashboard ("Mean Faithfulness")
     * @param direction FLOOR (observed >= threshold) or CEILING (observed <= threshold)
     * @param threshold the floor or ceiling value
     * @param observed  the actual value (for per-item: the minimum item score)
     * @param hard      true = breach fails the build; false = report-only
     * @param passed    true = within threshold
     * @param detail    optional extra ("all-above", "worst=fx-1@5", "")
     */
    public record MetricResult(
            String key,
            String label,
            Direction direction,
            double threshold,
            double observed,
            boolean hard,
            boolean passed,
            String detail
    ) {
        public String status() {
            return passed ? "PASS" : (hard ? "FAIL" : "WARN");
        }
        public String constraintStr() {
            return (direction == Direction.FLOOR ? ">=" : "<=") + " " + threshold;
        }
    }

    public record Outcome(List<String> passes, List<String> hardFailures, List<MetricResult> metrics) {
        public boolean isGreen() {
            return hardFailures.isEmpty();
        }
    }

    private GateEvaluator() {}

    public static Outcome evaluate(JsonNode report, Map<String, Object> thresholds) {
        List<MetricResult> metrics = new ArrayList<>();

        JsonNode agg = report.get("aggregate");
        if (agg == null) {
            return new Outcome(List.of(),
                    List.of("[gate] FAIL: report has no 'aggregate' node"),
                    List.of());
        }

        // ---- aggregate floors (mean*) ----
        addFloor(metrics,   thresholds, agg, "meanFaithfulness",    "Mean Faithfulness");
        addFloor(metrics,   thresholds, agg, "meanCitationF1",      "Mean Citation F1");
        addFloor(metrics,   thresholds, agg, "meanRetrievalRecall", "Mean Retrieval Recall");
        addFloor(metrics,   thresholds, agg, "meanCorrectness",     "Mean Correctness");
        addFloor(metrics,   thresholds, agg, "meanRelevance",       "Mean Relevance");

        // ---- aggregate ceiling (judgeParseFailures) ----
        addCeiling(metrics, thresholds, agg, "judgeParseFailures",  "Judge Parse Failures");

        // ---- per-item faithfulness floor (every item >= floor) ----
        addPerItemFloor(metrics, thresholds, report,
                "perItemFaithfulness", "faithfulnessScore", "Per-item Faithfulness");

        // ---- derive the string lists from the structured metrics ----
        List<String> passes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (MetricResult m : metrics) {
            String msg = format(m);
            if (m.passed() || !m.hard()) {
                passes.add(msg);
            } else {
                failures.add(msg);
            }
        }
        return new Outcome(passes, failures, metrics);
    }

    @SuppressWarnings("unchecked")
    private static void addFloor(List<MetricResult> out, Map<String, Object> th, JsonNode agg,
                                 String key, String label) {
        Object raw = th.get(key);
        if (!(raw instanceof Map)) return;
        Map<String, Object> cfg = (Map<String, Object>) raw;
        double floor = ((Number) cfg.get("floor")).doubleValue();
        boolean hard = Boolean.TRUE.equals(cfg.get("hard"));
        JsonNode node = agg.get(key);
        if (node == null) {
            out.add(new MetricResult(key, label, Direction.FLOOR, floor, Double.NaN, hard, false, "NOT-FOUND"));
            return;
        }
        double observed = node.asDouble();
        out.add(new MetricResult(key, label, Direction.FLOOR, floor, observed, hard, observed >= floor, ""));
    }

    @SuppressWarnings("unchecked")
    private static void addCeiling(List<MetricResult> out, Map<String, Object> th, JsonNode agg,
                                   String key, String label) {
        Object raw = th.get(key);
        if (!(raw instanceof Map)) return;
        Map<String, Object> cfg = (Map<String, Object>) raw;
        double ceiling = ((Number) cfg.get("ceiling")).doubleValue();
        boolean hard = Boolean.TRUE.equals(cfg.get("hard"));
        JsonNode node = agg.get(key);
        if (node == null) {
            out.add(new MetricResult(key, label, Direction.CEILING, ceiling, Double.NaN, hard, false, "NOT-FOUND"));
            return;
        }
        double observed = node.asDouble();
        out.add(new MetricResult(key, label, Direction.CEILING, ceiling, observed, hard, observed <= ceiling, ""));
    }

    @SuppressWarnings("unchecked")
    private static void addPerItemFloor(List<MetricResult> out, Map<String, Object> th, JsonNode report,
                                        String key, String itemField, String label) {
        Object raw = th.get(key);
        if (!(raw instanceof Map)) return;
        Map<String, Object> cfg = (Map<String, Object>) raw;
        double floor = ((Number) cfg.get("floor")).doubleValue();
        boolean hard = Boolean.TRUE.equals(cfg.get("hard"));
        JsonNode items = report.get("items");
        if (items == null || !items.isArray()) {
            out.add(new MetricResult(key, label, Direction.FLOOR, floor, Double.NaN, hard, false, "items[] missing"));
            return;
        }
        int below = 0;
        String worstId = null;
        int worstScore = Integer.MAX_VALUE;
        for (JsonNode it : items) {
            int score = it.get(itemField).asInt();
            if (score < 0) continue; // -1 = judge parse fail, surfaced via judgeParseFailures
            if (score < worstScore) {
                worstScore = score;
                worstId = it.get("id").asText();
            }
            if (score < floor) below++;
        }
        // For the PASS case, "observed" is the minimum item score across the corpus.
        // For the FAIL case, "observed" is also the minimum (the worst offender).
        boolean passed = (below == 0);
        String detail = passed
                ? "all-above"
                : String.format("worst=%s@%d (%d below)", worstId, worstScore, below);
        out.add(new MetricResult(key, label, Direction.FLOOR, floor,
                worstScore == Integer.MAX_VALUE ? Double.NaN : worstScore,
                hard, passed, detail));
    }

    /** String form preserved (modulo per-item formatting) so existing callers + log scrapers behave. */
    private static String format(MetricResult m) {
        String tag = m.status();
        if (m.detail().equals("all-above")) {
            return String.format("[gate] %s metric=%-22s observed=%7s threshold>=%.0f (every item)",
                    tag, m.key(), "all-above", m.threshold());
        }
        if (m.detail().startsWith("worst=")) {
            return String.format("[gate] %s metric=%-22s observed=%s threshold>=%.0f",
                    tag, m.key(), m.detail(), m.threshold());
        }
        if (m.detail().equals("NOT-FOUND")) {
            return String.format("[gate] FAIL metric=%s NOT-FOUND in report.aggregate", m.key());
        }
        if (m.direction() == Direction.CEILING) {
            return String.format("[gate] %s metric=%-22s observed=%7.0f threshold<=%.0f",
                    tag, m.key(), m.observed(), m.threshold());
        }
        return String.format("[gate] %s metric=%-22s observed=%7.3f threshold>=%.3f",
                tag, m.key(), m.observed(), m.threshold());
    }
}
