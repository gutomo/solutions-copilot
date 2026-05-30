package com.example.copilot.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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
            String subjectModel,
            String judgeModel,
            Aggregate aggregate,
            List<Item> items
    ) {}

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
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

    public static void writeJson(Path file, Report report) throws IOException {
        Files.createDirectories(file.getParent());
        mapper().writeValue(file.toFile(), report);
    }

    public static void writeMarkdown(Path file, Report report) throws IOException {
        Aggregate a = report.aggregate();
        StringBuilder sb = new StringBuilder();
        sb.append("# Eval report\n\n");
        sb.append("- timestamp: ").append(report.timestamp()).append("\n");
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

    public static void printConsole(Report report) {
        Aggregate a = report.aggregate();
        System.out.println();
        System.out.println("=== EVAL REPORT ===");
        System.out.println("subject : " + report.subjectModel());
        System.out.println("judge   : " + report.judgeModel());
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
}
