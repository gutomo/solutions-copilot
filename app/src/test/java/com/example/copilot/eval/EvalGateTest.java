package com.example.copilot.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Live enforcer: reads target/eval-report.json (written by `mvn test -Peval`)
 * and src/test/resources/eval-thresholds.yml, then fails the build if any
 * HARD threshold is breached. Soft thresholds are reported but never fail.
 *
 * Runs under its own profile (`mvn test -Pgate`) AFTER the eval has written
 * the report. The threshold-checking logic itself lives in GateEvaluator and
 * is covered separately by EvalGateFixtureTest (default `mvn test`), which
 * guarantees the gate keeps biting on every build, not just on the live run.
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

        Map<String, Object> thresholds;
        try (InputStream in = new ClassPathResource(THRESHOLDS_RESOURCE).getInputStream()) {
            thresholds = new Yaml().load(in);
        }

        GateEvaluator.Outcome outcome = GateEvaluator.evaluate(report, thresholds);

        System.out.println();
        System.out.println("=== EVAL GATE ===");
        outcome.passes().forEach(System.out::println);
        if (!outcome.isGreen()) {
            outcome.hardFailures().forEach(System.out::println);
            System.out.println();
            fail("[gate] FAIL: " + outcome.hardFailures().size() + " hard threshold(s) breached");
        }
        System.out.println("[gate] PASS: all hard thresholds satisfied");
    }
}
