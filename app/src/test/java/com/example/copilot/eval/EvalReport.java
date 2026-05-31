package com.example.copilot.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class EvalReport {

    private EvalReport() {}

    public record Aggregate(
            int totalItems,
            int judgeParseFailures,
            double meanFaithfulness,
            double meanRelevance,
            double meanCorrectness,
            double meanCitationF1,
            double meanRetrievalRecall,
            double goodCitationRate
    ) {}

    public record Item(
            String id,
            String question,
            boolean answerable,
            String answer,
            List<String> retrievedSources,
            List<String> citedSources,
            List<String> expectedSources,
            int faithfulnessScore,
            String faithfulnessReason,
            int relevanceScore,
            String relevanceReason,
            double correctnessRequired,
            double correctnessForbidden,
            double correctnessOverall,
            double citationPrecision,
            double citationRecall,
            double citationF1,
            double retrievalRecall
    ) {}

    public record Report(
            OffsetDateTime timestamp,
            String gitSha,
            String subjectModel,
            String judgeModel,
            Aggregate aggregate,
            List<Item> items
    ) {}

    /** One row of eval-history.jsonl. Aggregate-only; per-item detail stays in the per-run JSON. */
    public record HistoryEntry(
            OffsetDateTime timestamp,
            String gitSha,
            String subject,
            String judge,
            double meanFaithfulness,
            double meanRelevance,
            double meanCorrectness,
            double meanCitationF1,
            double meanRetrievalRecall,
            int judgeParseFailures
    ) {
        public static HistoryEntry fromReport(Report r) {
            Aggregate a = r.aggregate();
            return new HistoryEntry(r.timestamp(), r.gitSha(), r.subjectModel(), r.judgeModel(),
                    a.meanFaithfulness(), a.meanRelevance(), a.meanCorrectness(),
                    a.meanCitationF1(), a.meanRetrievalRecall(), a.judgeParseFailures());
        }
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static ObjectMapper compactMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static Aggregate aggregate(List<Item> items) {
        int total = items.size();
        int parseFails = (int) items.stream()
                .filter(i -> i.faithfulnessScore() < 0 || i.relevanceScore() < 0)
                .count();
        double meanFaith = mean(items.stream().mapToInt(Item::faithfulnessScore).filter(s -> s >= 0));
        double meanRel = mean(items.stream().mapToInt(Item::relevanceScore).filter(s -> s >= 0));
        double meanCorr = mean(items.stream().mapToDouble(Item::correctnessOverall));
        double meanCiteF1 = mean(items.stream().mapToDouble(Item::citationF1));
        double meanRetRec = mean(items.stream().mapToDouble(Item::retrievalRecall));
        double goodCite = total == 0 ? 0.0
                : items.stream().filter(i -> i.citationF1() >= 0.5).count() / (double) total;
        return new Aggregate(total, parseFails, meanFaith, meanRel, meanCorr, meanCiteF1, meanRetRec, goodCite);
    }

    private static double mean(java.util.stream.IntStream s) {
        var stats = s.summaryStatistics();
        return stats.getCount() == 0 ? 0.0 : stats.getAverage();
    }

    private static double mean(java.util.stream.DoubleStream s) {
        var stats = s.summaryStatistics();
        return stats.getCount() == 0 ? 0.0 : stats.getAverage();
    }

    // ============================== JSON ==============================

    public static void writeJson(Path file, Report report) throws IOException {
        Files.createDirectories(file.getParent());
        mapper().writeValue(file.toFile(), report);
    }

    // ============================== Markdown ==============================

    public static void writeMarkdown(Path file, Report report) throws IOException {
        Aggregate a = report.aggregate();
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval report\n\n");
        sb.append("- timestamp: ").append(report.timestamp()).append("\n");
        sb.append("- git sha:   ").append(report.gitSha() == null ? "unknown" : report.gitSha()).append("\n");
        sb.append("- subject model: `").append(report.subjectModel()).append("`\n");
        sb.append("- judge model:   `").append(report.judgeModel()).append("`\n");
        sb.append("- items: ").append(a.totalItems()).append("\n");
        sb.append("- judge parse failures: ").append(a.judgeParseFailures()).append("\n\n");
        sb.append("## Aggregate\n\n");
        sb.append(String.format("| metric | value |%n|---|---|%n"));
        sb.append(String.format("| mean faithfulness | %.1f |%n", a.meanFaithfulness()));
        sb.append(String.format("| mean relevance | %.1f |%n", a.meanRelevance()));
        sb.append(String.format("| mean correctness | %.2f |%n", a.meanCorrectness()));
        sb.append(String.format("| mean citation F1 | %.2f |%n", a.meanCitationF1()));
        sb.append(String.format("| mean retrieval recall | %.2f |%n", a.meanRetrievalRecall()));
        sb.append(String.format("| good citation rate (F1 >= 0.5) | %.2f |%n", a.goodCitationRate()));
        sb.append("\n## Worst 3 by faithfulness\n\n");
        List<Item> worst = report.items().stream()
                .filter(i -> i.faithfulnessScore() >= 0)
                .sorted(Comparator.comparingInt(Item::faithfulnessScore))
                .limit(3)
                .collect(Collectors.toList());
        for (Item it : worst) {
            sb.append("### ").append(it.id()).append("  (faith=").append(it.faithfulnessScore()).append(")\n\n");
            sb.append("- question: ").append(it.question()).append("\n");
            sb.append("- expected sources: ").append(it.expectedSources()).append("\n");
            sb.append("- cited sources: ").append(it.citedSources()).append("\n");
            sb.append("- retrieved sources: ").append(it.retrievedSources()).append("\n");
            sb.append("- correctness overall: ").append(String.format("%.2f", it.correctnessOverall())).append("\n");
            sb.append("- citation F1: ").append(String.format("%.2f", it.citationF1())).append("\n");
            sb.append("- judge reason: ").append(it.faithfulnessReason()).append("\n\n");
            sb.append("answer:\n\n> ").append(it.answer().replace("\n", "\n> ")).append("\n\n");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString());
    }

    // ============================== Console ==============================

    public static void printConsole(Report report) {
        Aggregate a = report.aggregate();
        System.out.println();
        System.out.println("=== EVAL REPORT ===");
        System.out.println("subject : " + report.subjectModel());
        System.out.println("judge   : " + report.judgeModel());
        System.out.println("git sha : " + (report.gitSha() == null ? "unknown" : report.gitSha()));
        System.out.println("items   : " + a.totalItems() + "  judge parse failures: " + a.judgeParseFailures());
        System.out.println();
        System.out.printf("%-40s %5s %5s %6s %6s %6s  cited%n",
                "id", "faith", "rel", "corr", "citeF1", "retR");
        System.out.println("-".repeat(110));
        for (Item it : report.items()) {
            System.out.printf("%-40s %5d %5d %6.2f %6.2f %6.2f  %s%n",
                    it.id(),
                    it.faithfulnessScore(),
                    it.relevanceScore(),
                    it.correctnessOverall(),
                    it.citationF1(),
                    it.retrievalRecall(),
                    it.citedSources());
        }
        System.out.println("-".repeat(110));
        System.out.printf("AGGREGATE  faith=%.1f  rel=%.1f  corr=%.2f  citeF1=%.2f  retRecall=%.2f  goodCite=%.2f%n",
                a.meanFaithfulness(), a.meanRelevance(), a.meanCorrectness(),
                a.meanCitationF1(), a.meanRetrievalRecall(), a.goodCitationRate());
        System.out.println();
    }

    // ============================== History (jsonl) ==============================

    public static void appendHistory(Path file, HistoryEntry entry) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        String line = compactMapper().writeValueAsString(entry) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static List<HistoryEntry> readHistory(Path file) {
        if (!Files.exists(file)) return List.of();
        ObjectMapper m = compactMapper();
        List<HistoryEntry> out = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    out.add(m.readValue(line, HistoryEntry.class));
                } catch (IOException badLine) {
                    // tolerate one bad line rather than losing the whole trend
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    // ============================== HTML ==============================

    /**
     * Self-contained single-file dashboard. Data is server-rendered into DOM
     * (so the page is readable with JS disabled and assertable by regex over
     * markup) AND echoed into a JS object solely for the click-to-expand UX.
     * No CDN, no external CSS, no fetch -- opens by double-click via file://.
     */
    public static void writeHtml(Path file, Report report,
                                 GateEvaluator.Outcome gate,
                                 List<HistoryEntry> history) throws IOException {
        Aggregate a = report.aggregate();
        StringBuilder sb = new StringBuilder(64 * 1024);

        sb.append("<!doctype html>\n");
        sb.append("<html lang=\"en\"><head><meta charset=\"utf-8\">\n");
        sb.append("<title>Solutions Copilot - Eval Report</title>\n");
        sb.append("<style>").append(css()).append("</style>\n");
        sb.append("</head><body>\n");

        // ---- header ----
        sb.append("<header>\n");
        sb.append("<h1>Solutions Copilot - Eval Report</h1>\n");
        sb.append("<dl class=\"meta\">\n");
        sb.append("<dt>Subject</dt><dd class=\"mono\" data-key=\"subject\">").append(esc(report.subjectModel())).append("</dd>\n");
        sb.append("<dt>Judge</dt>  <dd class=\"mono\" data-key=\"judge\">").append(esc(report.judgeModel())).append("</dd>\n");
        sb.append("<dt>Timestamp</dt><dd class=\"mono\" data-key=\"timestamp\">").append(esc(String.valueOf(report.timestamp()))).append("</dd>\n");
        sb.append("<dt>Git SHA</dt><dd class=\"mono\" data-key=\"gitSha\">").append(esc(report.gitSha() == null ? "unknown" : report.gitSha())).append("</dd>\n");
        sb.append("<dt>Items</dt><dd data-key=\"totalItems\">").append(a.totalItems()).append("</dd>\n");
        sb.append("<dt>Gate</dt><dd data-key=\"gateStatus\" class=\"")
                .append(gate.isGreen() ? "ok" : "bad").append("\">")
                .append(gate.isGreen() ? "PASS" : "FAIL (" + gate.hardFailures().size() + " breach(es))")
                .append("</dd>\n");
        sb.append("</dl>\n</header>\n");

        // ---- metric cards ----
        sb.append("<section class=\"metrics\">\n<h2>Metrics vs gate thresholds</h2>\n<div class=\"grid\">\n");
        for (GateEvaluator.MetricResult m : gate.metrics()) {
            sb.append(renderCard(m));
        }
        sb.append("</div>\n</section>\n");

        // ---- worst items ----
        List<Item> worst = report.items().stream()
                .filter(i -> i.faithfulnessScore() >= 0)
                .sorted(Comparator.comparingInt(Item::faithfulnessScore))
                .limit(3)
                .collect(Collectors.toList());
        sb.append("<section class=\"worst\">\n<h2>Worst 3 by faithfulness</h2>\n<ol>\n");
        for (Item it : worst) {
            sb.append("<li data-id=\"").append(esc(it.id())).append("\">")
                    .append("<span class=\"score\">faith=").append(it.faithfulnessScore()).append("</span> ")
                    .append("<span class=\"mono\">").append(esc(it.id())).append("</span> &mdash; ")
                    .append(esc(it.faithfulnessReason()))
                    .append("</li>\n");
        }
        sb.append("</ol>\n</section>\n");

        // ---- per-question table ----
        sb.append("<section class=\"items\">\n<h2>Per-question detail</h2>\n");
        sb.append("<table class=\"items-table\"><thead><tr>");
        sb.append("<th>id</th><th>ans?</th><th>faith</th><th>rel</th><th>corr</th><th>citeF1</th><th>retR</th>");
        sb.append("<th>cited</th><th>expected</th>");
        sb.append("</tr></thead><tbody>\n");
        for (Item it : report.items()) {
            sb.append("<tr data-id=\"").append(esc(it.id())).append("\"")
                    .append(" data-faith=\"").append(it.faithfulnessScore()).append("\"")
                    .append(" data-rel=\"").append(it.relevanceScore()).append("\"")
                    .append(" data-corr=\"").append(fmt2(it.correctnessOverall())).append("\"")
                    .append(" data-citef1=\"").append(fmt2(it.citationF1())).append("\"")
                    .append(" data-retr=\"").append(fmt2(it.retrievalRecall())).append("\"")
                    .append(">");
            sb.append("<td class=\"mono\">").append(esc(it.id())).append("</td>");
            sb.append("<td>").append(it.answerable() ? "&#10003;" : "&times;").append("</td>");
            sb.append("<td class=\"num ").append(scoreClass(it.faithfulnessScore(), 60)).append("\">").append(it.faithfulnessScore()).append("</td>");
            sb.append("<td class=\"num ").append(scoreClass(it.relevanceScore(), 60)).append("\">").append(it.relevanceScore()).append("</td>");
            sb.append("<td class=\"num\">").append(fmt2(it.correctnessOverall())).append("</td>");
            sb.append("<td class=\"num\">").append(fmt2(it.citationF1())).append("</td>");
            sb.append("<td class=\"num\">").append(fmt2(it.retrievalRecall())).append("</td>");
            sb.append("<td>").append(renderSources(it.citedSources(), it.expectedSources(), true)).append("</td>");
            sb.append("<td>").append(renderSources(it.expectedSources(), it.expectedSources(), false)).append("</td>");
            sb.append("</tr>\n");
            sb.append("<tr class=\"detail-row\"><td colspan=\"9\"><details><summary>question + answer + judge reasons</summary>");
            sb.append("<dl class=\"detail\">");
            sb.append("<dt>question</dt><dd>").append(esc(it.question())).append("</dd>");
            sb.append("<dt>answer</dt><dd><pre>").append(esc(it.answer())).append("</pre></dd>");
            sb.append("<dt>faithfulness reason</dt><dd>").append(esc(it.faithfulnessReason())).append("</dd>");
            sb.append("<dt>relevance reason</dt><dd>").append(esc(it.relevanceReason())).append("</dd>");
            sb.append("<dt>retrieved sources</dt><dd class=\"mono\">").append(esc(it.retrievedSources().toString())).append("</dd>");
            sb.append("</dl></details></td></tr>\n");
        }
        sb.append("</tbody></table>\n</section>\n");

        // ---- trend (only if >= 2 runs) ----
        if (history.size() >= 2) {
            List<HistoryEntry> recent = history.size() > 20
                    ? history.subList(history.size() - 20, history.size())
                    : history;
            sb.append("<section class=\"trend\">\n<h2>Trend (last ").append(recent.size()).append(" runs)</h2>\n");
            sb.append("<div class=\"sparklines\">\n");
            for (GateEvaluator.MetricResult m : gate.metrics()) {
                List<Double> series = seriesFor(m.key(), recent);
                if (series.isEmpty()) continue;
                sb.append("<figure data-metric=\"").append(esc(m.key())).append("\">");
                sb.append("<figcaption>").append(esc(m.label())).append("</figcaption>");
                sb.append(sparkline(series, m.threshold(), m.direction()));
                sb.append("</figure>\n");
            }
            sb.append("</div>\n</section>\n");
        }

        sb.append("<footer><small>Generated by Solutions Copilot eval harness. Self-contained: no external network references; opens offline.</small></footer>\n");
        sb.append("</body></html>\n");

        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String renderCard(GateEvaluator.MetricResult m) {
        String status = m.status(); // PASS / FAIL / WARN
        String cssStatus = status.toLowerCase(java.util.Locale.ROOT);
        String observed;
        if (Double.isNaN(m.observed())) {
            observed = "(missing)";
        } else if (m.direction() == GateEvaluator.Direction.CEILING) {
            observed = String.format(java.util.Locale.ROOT, "%.0f", m.observed());
        } else if (m.threshold() >= 1.0) {
            observed = String.format(java.util.Locale.ROOT, "%.1f", m.observed());
        } else {
            observed = String.format(java.util.Locale.ROOT, "%.2f", m.observed());
        }
        String thresholdLabel = (m.direction() == GateEvaluator.Direction.CEILING ? "&le; " : "&ge; ")
                + (m.threshold() >= 1.0
                    ? String.format(java.util.Locale.ROOT, "%.0f", m.threshold())
                    : String.format(java.util.Locale.ROOT, "%.2f", m.threshold()));
        String badge = m.hard() ? "<span class=\"badge gated\">gated</span>"
                                : "<span class=\"badge report-only\">report-only</span>";
        StringBuilder s = new StringBuilder();
        s.append("<article class=\"card ").append(cssStatus).append(m.hard() ? " hard" : " soft")
                .append("\" data-key=\"").append(esc(m.key()))
                .append("\" data-hard=\"").append(m.hard())
                .append("\" data-passed=\"").append(m.passed()).append("\">");
        s.append("<div class=\"label\">").append(esc(m.label())).append(" ").append(badge).append("</div>");
        s.append("<div class=\"observed\" data-observed=\"").append(esc(observed)).append("\">").append(observed).append("</div>");
        s.append("<div class=\"threshold\">").append(thresholdLabel).append("</div>");
        s.append("<div class=\"status\">").append(status).append("</div>");
        if (!m.detail().isEmpty()) {
            s.append("<div class=\"detail\">").append(esc(m.detail())).append("</div>");
        }
        s.append("</article>\n");
        return s.toString();
    }

    private static String renderSources(List<String> shown, List<String> expected, boolean diff) {
        if (shown.isEmpty()) return "<span class=\"muted\">&mdash;</span>";
        StringBuilder s = new StringBuilder();
        for (String src : shown) {
            String mark = "";
            if (diff) {
                mark = expected.contains(src) ? " <span class=\"hit\">&#10003;</span>" : " <span class=\"miss\">&times;</span>";
            }
            s.append("<span class=\"src mono\">").append(esc(src)).append("</span>").append(mark).append(" ");
        }
        return s.toString().trim();
    }

    private static String scoreClass(int score, int floor) {
        if (score < 0) return "score-na";
        return score < floor ? "score-bad" : "";
    }

    private static List<Double> seriesFor(String metricKey, List<HistoryEntry> h) {
        List<Double> out = new ArrayList<>(h.size());
        for (HistoryEntry e : h) {
            switch (metricKey) {
                case "meanFaithfulness":    out.add(e.meanFaithfulness()); break;
                case "meanRelevance":       out.add(e.meanRelevance()); break;
                case "meanCorrectness":     out.add(e.meanCorrectness()); break;
                case "meanCitationF1":      out.add(e.meanCitationF1()); break;
                case "meanRetrievalRecall": out.add(e.meanRetrievalRecall()); break;
                case "judgeParseFailures":  out.add((double) e.judgeParseFailures()); break;
                default: /* perItemFaithfulness not in history; skip */ return List.of();
            }
        }
        return out;
    }

    private static String sparkline(List<Double> series, double threshold, GateEvaluator.Direction direction) {
        final int W = 240, H = 60, PAD = 8;
        double minData = series.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maxData = series.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double yMin = Math.min(minData, threshold);
        double yMax = Math.max(maxData, threshold);
        if (yMax - yMin < 1e-9) { yMin -= 1; yMax += 1; }
        double pad = (yMax - yMin) * 0.10;
        yMin -= pad;
        yMax += pad;
        double xStep = series.size() <= 1 ? 0 : (double) (W - 2 * PAD) / (series.size() - 1);

        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < series.size(); i++) {
            double x = PAD + i * xStep;
            double y = H - PAD - (series.get(i) - yMin) / (yMax - yMin) * (H - 2 * PAD);
            if (i > 0) pts.append(' ');
            pts.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f", x, y));
        }
        double yThresh = H - PAD - (threshold - yMin) / (yMax - yMin) * (H - 2 * PAD);
        double lastY = H - PAD - (series.get(series.size() - 1) - yMin) / (yMax - yMin) * (H - 2 * PAD);
        double lastX = PAD + (series.size() - 1) * xStep;

        String thresholdLabel = (direction == GateEvaluator.Direction.CEILING ? "<= " : ">= ")
                + (threshold >= 1.0
                    ? String.format(java.util.Locale.ROOT, "%.0f", threshold)
                    : String.format(java.util.Locale.ROOT, "%.2f", threshold));
        return "<svg viewBox=\"0 0 " + W + " " + H + "\" width=\"" + W + "\" height=\"" + H + "\" role=\"img\">"
                + "<line x1=\"" + PAD + "\" x2=\"" + (W - PAD) + "\" y1=\"" + String.format(java.util.Locale.ROOT, "%.1f", yThresh) + "\" y2=\"" + String.format(java.util.Locale.ROOT, "%.1f", yThresh) + "\" class=\"thr\"/>"
                + "<polyline points=\"" + pts + "\" class=\"line\"/>"
                + "<circle cx=\"" + String.format(java.util.Locale.ROOT, "%.1f", lastX) + "\" cy=\"" + String.format(java.util.Locale.ROOT, "%.1f", lastY) + "\" r=\"3\" class=\"dot\"/>"
                + "<text x=\"" + (W - PAD) + "\" y=\"" + (PAD + 4) + "\" class=\"thr-label\">" + thresholdLabel + "</text>"
                + "</svg>";
    }

    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':  out.append("&lt;"); break;
                case '>':  out.append("&gt;"); break;
                case '&':  out.append("&amp;"); break;
                case '"':  out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default:   out.append(c);
            }
        }
        return out.toString();
    }

    private static String css() {
        return """
                :root { --ok:#2e7d32; --bad:#c62828; --warn:#ef6c00; --muted:#757575; --bg:#fafafa;
                        --card:#fff; --border:#e0e0e0; --accent:#1565c0; }
                * { box-sizing: border-box; }
                body { margin:0; padding:24px; background:var(--bg); color:#212121;
                       font:14px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; }
                h1 { font-size:20px; margin:0 0 12px; }
                h2 { font-size:16px; margin:24px 0 8px; }
                .mono { font-family:Consolas,Menlo,Monaco,monospace; font-size:12.5px; }
                .muted { color:var(--muted); }
                .ok   { color:var(--ok);   font-weight:600; }
                .bad  { color:var(--bad);  font-weight:600; }
                .warn { color:var(--warn); font-weight:600; }
                header dl.meta { display:grid; grid-template-columns:auto 1fr; gap:4px 16px; margin:0; max-width:900px; }
                header dl.meta dt { color:var(--muted); }
                header dl.meta dd { margin:0; }
                section { background:var(--card); border:1px solid var(--border); border-radius:6px;
                          padding:16px; margin-top:16px; }
                .grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(220px, 1fr)); gap:12px; }
                .card { border:1px solid var(--border); border-radius:4px; padding:12px; background:#fff;
                        border-left-width:4px; }
                .card.pass { border-left-color:var(--ok); }
                .card.fail { border-left-color:var(--bad); }
                .card.warn { border-left-color:var(--warn); }
                .card .label { display:flex; align-items:center; justify-content:space-between;
                               font-weight:600; margin-bottom:6px; }
                .card .observed { font-size:28px; font-variant-numeric:tabular-nums; }
                .card .threshold { color:var(--muted); font-size:12px; font-variant-numeric:tabular-nums; }
                .card .status { margin-top:6px; font-weight:600; font-size:12px; }
                .card.pass .status { color:var(--ok); }
                .card.fail .status { color:var(--bad); }
                .card.warn .status { color:var(--warn); }
                .card .detail { color:var(--muted); font-size:11px; margin-top:4px; }
                .badge { font-size:10px; font-weight:600; padding:2px 6px; border-radius:3px;
                         background:#eee; color:#333; }
                .badge.gated { background:#e3f2fd; color:#0d47a1; }
                .badge.report-only { background:#f5f5f5; color:#616161; }
                .worst ol { margin:0; padding-left:20px; }
                .worst li { margin:4px 0; }
                .worst .score { color:var(--bad); font-weight:600; }
                table.items-table { width:100%; border-collapse:collapse; font-size:12.5px; }
                table.items-table th, table.items-table td { padding:6px 8px; border-bottom:1px solid var(--border);
                                                              text-align:left; vertical-align:top; }
                table.items-table th { background:#f5f5f5; color:#555; font-weight:600; }
                table.items-table td.num { text-align:right; font-variant-numeric:tabular-nums; }
                table.items-table td.score-bad { color:var(--bad); font-weight:600; }
                .src { display:inline-block; padding:1px 4px; margin:1px 2px; background:#f5f5f5; border-radius:3px; }
                .hit  { color:var(--ok);  font-weight:bold; }
                .miss { color:var(--bad); font-weight:bold; }
                .detail-row td { background:#fafafa; padding-top:0; padding-bottom:0; }
                .detail-row details { padding:6px 0; }
                .detail-row summary { cursor:pointer; color:var(--accent); font-size:12px; }
                .detail-row dl.detail { display:grid; grid-template-columns:160px 1fr; gap:4px 12px;
                                        margin:8px 0 12px; font-size:12.5px; }
                .detail-row dl.detail dt { color:var(--muted); }
                .detail-row dl.detail dd { margin:0; }
                .detail-row pre { white-space:pre-wrap; margin:0; font-family:Consolas,Menlo,monospace;
                                  font-size:12px; background:#fff; padding:6px; border:1px solid var(--border); border-radius:3px; }
                .sparklines { display:grid; grid-template-columns:repeat(auto-fill, minmax(260px, 1fr)); gap:12px; }
                .sparklines figure { margin:0; padding:8px; border:1px solid var(--border); border-radius:4px; background:#fff; }
                .sparklines figcaption { font-size:12px; color:#555; margin-bottom:4px; }
                .sparklines svg .line { fill:none; stroke:var(--accent); stroke-width:1.5; }
                .sparklines svg .thr  { stroke:var(--muted); stroke-width:1; stroke-dasharray:3,3; }
                .sparklines svg .dot  { fill:var(--accent); }
                .sparklines svg .thr-label { font-size:9px; fill:var(--muted); text-anchor:end; font-family:Consolas,monospace; }
                footer { color:var(--muted); font-size:11px; text-align:center; margin-top:24px; }
                """;
    }
}
