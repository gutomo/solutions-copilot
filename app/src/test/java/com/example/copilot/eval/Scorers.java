package com.example.copilot.eval;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

@Component
public class Scorers {

    private static final Logger log = LoggerFactory.getLogger(Scorers.class);

    private static final Pattern SCORE = Pattern.compile("SCORE\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REASON = Pattern.compile("REASON\\s*=\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private final JudgeClient judge;

    public Scorers(JudgeClient judge) {
        this.judge = judge;
    }

    public record JudgeScore(int score, String reason) {}
    public record CitationScore(double precision, double recall, double f1,
                                List<String> cited) {}
    public record CorrectnessScore(double requiredScore, double forbiddenScore, double overall) {}

    // ---- LLM-as-judge: faithfulness ----

    public JudgeScore faithfulness(String question, String answer, List<Document> retrieved) {
        String context = retrieved.stream()
                .map(d -> "[source=" + d.getMetadata().get("source") + "] " + d.getText())
                .collect(Collectors.joining("\n\n---\n\n"));
        String prompt = """
                You are evaluating whether an AI assistant's answer is grounded in the
                provided context. Score 0-100. 100 means every factual claim in the
                answer is directly supported by the context. 0 means the answer
                contains fabricated facts that the context does not support. If the
                answer correctly declines because the context lacks the information,
                score 100. Output ONLY one line in the exact form:
                SCORE=<int> REASON=<one short sentence>

                CONTEXT (top-5 retrieved chunks):
                %s

                QUESTION: %s
                ANSWER: %s
                """.formatted(context, question, answer);
        return parse(judge.call(prompt));
    }

    // ---- LLM-as-judge: relevance ----

    public JudgeScore relevance(String question, String answer) {
        String prompt = """
                You are evaluating whether an answer addresses the question,
                regardless of factual accuracy. Score 0-100. 100 means the answer
                directly engages with what was asked. A clear and appropriate
                refusal (the question cannot be answered from the available
                material) scores 80. An off-topic answer scores 0. Output ONLY
                one line in the exact form:
                SCORE=<int> REASON=<one short sentence>

                QUESTION: %s
                ANSWER: %s
                """.formatted(question, answer);
        return parse(judge.call(prompt));
    }

    private JudgeScore parse(String verdict) {
        Matcher sm = SCORE.matcher(verdict);
        Matcher rm = REASON.matcher(verdict);
        if (!sm.find()) {
            log.warn("[scorer] judge verdict unparseable, marking -1. raw='{}'", verdict.replace('\n', ' '));
            return new JudgeScore(-1, "UNPARSED: " + verdict.replace('\n', ' ').strip());
        }
        int score = Integer.parseInt(sm.group(1));
        String reason = rm.find() ? rm.group(1).trim() : "(no REASON= captured)";
        return new JudgeScore(score, reason);
    }

    // ---- Deterministic: citation ----

    public CitationScore citation(String answer, List<String> expectedSources,
                                  Set<String> allCorpusIds) {
        List<String> cited = allCorpusIds.stream()
                .filter(id -> answer.contains(id))
                .sorted()
                .collect(Collectors.toList());
        Set<String> expected = Set.copyOf(expectedSources);

        if (expected.isEmpty() && cited.isEmpty()) {
            return new CitationScore(1.0, 1.0, 1.0, cited);
        }
        if (expected.isEmpty()) {
            // Cited something we didn't expect.
            return new CitationScore(0.0, 1.0, 0.0, cited);
        }
        if (cited.isEmpty()) {
            return new CitationScore(0.0, 0.0, 0.0, cited);
        }
        long hits = cited.stream().filter(expected::contains).count();
        double precision = (double) hits / cited.size();
        double recall = (double) hits / expected.size();
        double f1 = (precision + recall == 0) ? 0.0 : 2 * precision * recall / (precision + recall);
        return new CitationScore(precision, recall, f1, cited);
    }

    // ---- Deterministic: retrieval recall (companion to citation) ----

    public double retrievalRecall(List<String> expectedSources, List<Document> retrieved) {
        if (expectedSources.isEmpty()) {
            return 1.0;
        }
        Set<String> retrievedSources = retrieved.stream()
                .map(d -> (String) d.getMetadata().get("source"))
                .filter(s -> s != null)
                .collect(Collectors.toSet());
        long hits = expectedSources.stream().filter(retrievedSources::contains).count();
        return (double) hits / expectedSources.size();
    }

    // ---- Deterministic: answer correctness vs required/forbidden facts ----
    // Slice 2: forbidden_facts only penalize when asserted as the CURRENT
    // answer. A forbidden value labeled as historical ("previously", "2025",
    // "superseded") is acceptable -- the model is correctly disambiguating,
    // not leaking the stale figure as the answer. required_facts stays the
    // deterministic backstop: if the current value is absent, correctness
    // still bites (this is what catches the planted stale-leak case).

    private static final List<String> HISTORY_MARKERS = List.of(
            "previous", "prior", "previously", "formerly", "earlier",
            "old", "older", "historical", "historic", "legacy",
            "superseded", "obsolete", "outdated", "deprecated",
            "2025",
            "was ", " were ", "had been", "used to be",
            "no longer", "before"
    );
    private static final int CONTEXT_WINDOW = 80;

    public CorrectnessScore correctness(boolean answerable,
                                        List<String> requiredFacts,
                                        List<String> forbiddenFacts,
                                        String answer) {
        String lower = answer.toLowerCase(Locale.ROOT);

        double requiredScore;
        if (requiredFacts.isEmpty()) {
            requiredScore = 1.0;
        } else if (answerable) {
            long hits = requiredFacts.stream()
                    .filter(f -> lower.contains(f.toLowerCase(Locale.ROOT)))
                    .count();
            requiredScore = (double) hits / requiredFacts.size();
        } else {
            // Unanswerable: any one refusal marker is sufficient.
            boolean any = requiredFacts.stream()
                    .anyMatch(f -> lower.contains(f.toLowerCase(Locale.ROOT)));
            requiredScore = any ? 1.0 : 0.0;
        }

        double forbiddenScore;
        if (forbiddenFacts.isEmpty()) {
            forbiddenScore = 1.0;
        } else {
            long unmarkedHits = forbiddenFacts.stream()
                    .filter(f -> hasUnmarkedOccurrence(lower, f.toLowerCase(Locale.ROOT)))
                    .count();
            forbiddenScore = 1.0 - ((double) unmarkedHits / forbiddenFacts.size());
        }

        return new CorrectnessScore(requiredScore, forbiddenScore, requiredScore * forbiddenScore);
    }

    /**
     * True iff the forbidden term occurs at least once in the answer with NO
     * history marker within +/- CONTEXT_WINDOW chars of that occurrence.
     * A single unmarked occurrence penalizes the term -- "current is X, was Y
     * in 2025" doesn't, but "the answer is Y" does.
     */
    private static boolean hasUnmarkedOccurrence(String lowerAnswer, String lowerForbidden) {
        int idx = 0;
        while (true) {
            int pos = lowerAnswer.indexOf(lowerForbidden, idx);
            if (pos < 0) {
                return false;
            }
            int wStart = Math.max(0, pos - CONTEXT_WINDOW);
            int wEnd = Math.min(lowerAnswer.length(), pos + lowerForbidden.length() + CONTEXT_WINDOW);
            String window = lowerAnswer.substring(wStart, wEnd);
            boolean marked = HISTORY_MARKERS.stream().anyMatch(window::contains);
            if (!marked) {
                return true;
            }
            idx = pos + lowerForbidden.length();
        }
    }
}
