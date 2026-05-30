package com.example.copilot.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.copilot.ingest.IngestionService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1 connects to a sidecar `eval-pg` started outside the test (port 5433,
 * database `copilot_eval`) instead of Testcontainers, because Docker Desktop
 * on Windows returns malformed responses to Linux-container Testcontainers
 * clients via the mounted socket. The launcher script starts a fresh eval-pg
 * per run, so the harness only truncates vector_store before seeding to
 * guarantee a known starting state. Slice 4 / CI on Linux runners will
 * switch this to a real Testcontainers @Container.
 */
@SpringBootTest
@ActiveProfiles("eval")
@Tag("eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalHarnessTest {

    private static final Logger log = LoggerFactory.getLogger(EvalHarnessTest.class);

    @Autowired ChatClient chatClient;
    @Autowired VectorStore vectorStore;
    @Autowired IngestionService ingestion;
    @Autowired Scorers scorers;
    @Autowired JudgeClient judge;
    @Autowired ObjectMapper jsonMapper;
    @Autowired JdbcTemplate jdbc;

    @Value("${spring.ai.bedrock.converse.chat.options.model}") String subjectModel;
    @Value("${eval.output.json}")     String jsonPath;
    @Value("${eval.output.markdown}") String mdPath;

    public record CorpusDoc(String id, String content) {}

    public record Question(
            String id,
            String question,
            boolean answerable,
            @JsonProperty("expected_sources") List<String> expectedSources,
            @JsonProperty("required_facts")   List<String> requiredFacts,
            @JsonProperty("forbidden_facts")  List<String> forbiddenFacts
    ) {}

    List<CorpusDoc> corpus;
    List<Question> questions;
    Set<String> allCorpusIds;

    @BeforeAll
    void loadAndSeed() throws IOException {
        corpus    = readJson("eval/corpus.json",    new TypeReference<List<CorpusDoc>>() {});
        questions = readJson("eval/questions.json", new TypeReference<List<Question>>() {});
        allCorpusIds = corpus.stream().map(CorpusDoc::id).collect(Collectors.toSet());

        // Safety net: refuse to TRUNCATE unless we are on the dedicated eval DB.
        // The eval profile defaults the datasource to copilot_eval, but a stray
        // DB_NAME override could point at the dev DB -- fail loud before any
        // destructive write rather than wiping dev data.
        String connectedDb = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(connectedDb)
                .as("eval must run against the dedicated 'copilot_eval' database, not '%s' "
                        + "-- refusing to TRUNCATE vector_store", connectedDb)
                .isEqualTo("copilot_eval");

        // Guarantee a known starting state regardless of prior runs against the
        // same eval-pg sidecar.
        int wiped = jdbc.update("TRUNCATE TABLE vector_store");
        log.info("[eval] truncated vector_store (rows affected report ignored): {}", wiped);

        log.info("[eval] seeding {} documents through IngestionService", corpus.size());
        int totalChunks = 0;
        for (CorpusDoc d : corpus) {
            var result = ingestion.ingest(d.id(), d.content());
            totalChunks += result.chunks();
            log.info("[eval] seeded id={} chunks={}", result.source(), result.chunks());
            // Watch-item: "Document ID: <id>" only rides the first chunk. Fail
            // loud if any doc splits so the citation metric isn't silently broken.
            assertThat(result.chunks())
                    .as("doc %s split into %d chunks; only the first carries the Document ID prefix, citation will under-report",
                            d.id(), result.chunks())
                    .isEqualTo(1);
        }
        log.info("[eval] seed complete  docs={} chunks={}", corpus.size(), totalChunks);
    }

    private <T> T readJson(String resource, TypeReference<T> type) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return jsonMapper.readValue(in, type);
        }
    }

    @Test
    void runEvaluation() throws IOException {
        log.info("[eval] subject={} judge={}", subjectModel, judge.judgeModelId());
        List<EvalReport.Item> items = new ArrayList<>();

        for (Question q : questions) {
            log.info("[eval] q={} question={}", q.id(), q.question());

            // Subject under test: real ChatClient (QA advisor + tools active),
            // running at prod config (temperature 0.3 from application.yml).
            String answer = chatClient.prompt().user(q.question()).call().content();
            answer = answer == null ? "" : answer;

            // Parallel similarity search: same topK as the QA advisor, so the
            // returned chunks are what the advisor injected into the prompt.
            List<Document> retrieved = vectorStore.similaritySearch(
                    SearchRequest.builder().query(q.question()).topK(5).build());
            List<String> retrievedSources = retrieved.stream()
                    .map(d -> (String) d.getMetadata().get("source"))
                    .filter(s -> s != null)
                    .collect(Collectors.toList());

            var faith = scorers.faithfulness(q.question(), answer, retrieved);
            var rel   = scorers.relevance(q.question(), answer);
            var corr  = scorers.correctness(q.answerable(), q.requiredFacts(), q.forbiddenFacts(), answer);
            var cite  = scorers.citation(answer, q.expectedSources(), allCorpusIds);
            double retRecall = scorers.retrievalRecall(q.expectedSources(), retrieved);

            items.add(new EvalReport.Item(
                    q.id(), q.question(), q.answerable(), answer,
                    retrievedSources, cite.cited(), q.expectedSources(),
                    faith.score(), faith.reason(),
                    rel.score(),   rel.reason(),
                    corr.requiredScore(), corr.forbiddenScore(), corr.overall(),
                    cite.precision(), cite.recall(), cite.f1(),
                    retRecall));
        }

        EvalReport.Report report = new EvalReport.Report(
                OffsetDateTime.now(),
                subjectModel,
                judge.judgeModelId(),
                EvalReport.aggregate(items),
                items);

        EvalReport.printConsole(report);
        EvalReport.writeJson(Path.of(jsonPath), report);
        EvalReport.writeMarkdown(Path.of(mdPath), report);
        log.info("[eval] wrote {} and {}", jsonPath, mdPath);

        // The only assertion: the harness completed and the report has all 20
        // items. No score gating in slice 1.
        assertThat(items).hasSize(questions.size());
        assertThat(Path.of(jsonPath)).exists();
        assertThat(Path.of(mdPath)).exists();
    }

    @Test
    void scorersBiteOnPlantedBadCases() {
        // Synthetic context the model would have "seen": two competing docs.
        Document doc2026 = new Document(
                "Document ID: policy-margin-floor-2026-q2\n\nThe Azure margin floor is 17.4 percent.",
                Map.of("source", "policy-margin-floor-2026-q2"));
        Document doc2025 = new Document(
                "Document ID: policy-margin-floor-2025-q4\n\nThe Azure margin floor is 16.8 percent.",
                Map.of("source", "policy-margin-floor-2025-q4"));
        List<Document> retrieved = List.of(doc2026, doc2025);
        Set<String> ids = Set.of("policy-margin-floor-2026-q2", "policy-margin-floor-2025-q4");

        // ---- 1. Hallucination: unsupported number ----
        String halluQ = "What is the Azure margin floor?";
        String halluA = "The Azure margin floor is 42.3 percent. Source: policy-margin-floor-2026-q2";
        var faithBad = scorers.faithfulness(halluQ, halluA, retrieved);
        System.out.printf("[planted:hallucination] faithfulness=%d reason=%s%n",
                faithBad.score(), faithBad.reason());
        // Soft check (LLM judge). If Haiku-self-judge scores it >= 50, the
        // report flags the judge-quality problem and recommends Sonnet.

        // ---- 2. Wrong citation: right number, wrong doc ----
        String wrongCiteA = "The current Azure margin floor is 17.4 percent. Source: policy-margin-floor-2025-q4";
        var wrongCite = scorers.citation(wrongCiteA,
                List.of("policy-margin-floor-2026-q2"), ids);
        System.out.printf("[planted:wrong-citation] precision=%.2f recall=%.2f f1=%.2f cited=%s%n",
                wrongCite.precision(), wrongCite.recall(), wrongCite.f1(), wrongCite.cited());
        assertThat(wrongCite.f1()).isLessThan(0.5);

        // ---- 3. Stale leak: disambiguation failure that faithfulness misses ----
        String staleA = "The current Azure margin floor for Premier-tier is 16.8 percent.";
        var staleCorr = scorers.correctness(true, List.of("17.4"), List.of("16.8"), staleA);
        System.out.printf("[planted:stale-leak] requiredScore=%.2f forbiddenScore=%.2f overall=%.2f%n",
                staleCorr.requiredScore(), staleCorr.forbiddenScore(), staleCorr.overall());
        assertThat(staleCorr.overall()).isLessThan(0.5);

        // ---- 4. Off-topic answer: relevance should drop ----
        String offTopicA = "The weather in Tokyo is mild today with a chance of light rain.";
        var relBad = scorers.relevance(halluQ, offTopicA);
        System.out.printf("[planted:off-topic] relevance=%d reason=%s%n",
                relBad.score(), relBad.reason());

        if (faithBad.score() >= 50 && faithBad.score() != -1) {
            System.out.println("[planted:hallucination] WARNING: judge did not flag hallucination -- "
                    + "recommend switching EVAL_JUDGE_MODEL to a Sonnet/Opus profile before any CI gating.");
        }
        if (relBad.score() >= 50 && relBad.score() != -1) {
            System.out.println("[planted:off-topic] WARNING: judge scored off-topic answer >= 50.");
        }
    }
}
