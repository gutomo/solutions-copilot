package com.example.copilot.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 4 slice 5, Layer 1: a deterministic, heuristic scan of document content
 * for prompt-injection idioms BEFORE it is chunked/embedded/stored. Cheap (no
 * model call) and config-driven ({@code copilot.security.injection.patterns},
 * id -> regex).
 *
 * <p>Robustness against trivial evasion: patterns are compiled
 * case-INSENSITIVE, and the content is whitespace-normalised (runs of
 * whitespace collapsed to a single space) before matching, so casing or odd
 * spacing/newlines do not slip an attack past the door.
 *
 * <p>This is one layer of defence-in-depth, NOT a complete fix: indirect prompt
 * injection has no perfect heuristic. A stronger (heavier) option is an
 * LLM-classifier -- which costs a model call per ingest and can itself be
 * injected. Layer 2 (prompt-level data/instruction isolation) is the durable
 * backstop for whatever evades these patterns.
 */
@Component
public class InjectionScanner {

    private static final Logger log = LoggerFactory.getLogger(InjectionScanner.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int SNIPPET_MAX = 80;

    private final boolean enabled;
    private final Map<String, Pattern> patterns;

    /** A scanner hit: the pattern id and a short snippet of the matched text (never the full doc). */
    public record Match(String patternId, String snippet) {}

    public InjectionScanner(SecurityProperties props) {
        this.enabled = props.injection().enabled();
        this.patterns = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : props.injection().patterns().entrySet()) {
            try {
                this.patterns.put(e.getKey(), Pattern.compile(e.getValue(), Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException ex) {
                // Fail loud at startup-ish (first ingest) rather than silently skipping a guard.
                throw new IllegalStateException(
                        "invalid copilot.security.injection.patterns." + e.getKey() + " regex", ex);
            }
        }
        log.info("[security] injection scanner enabled={} patterns={}", enabled, patterns.keySet());
    }

    /**
     * @return the first matching pattern (id + snippet), or empty if the scanner
     *         is disabled, the content is blank, or nothing matches.
     */
    public Optional<Match> scan(String content) {
        if (!enabled || content == null || content.isBlank()) {
            return Optional.empty();
        }
        String normalised = WHITESPACE.matcher(content).replaceAll(" ");
        for (Map.Entry<String, Pattern> e : patterns.entrySet()) {
            Matcher m = e.getValue().matcher(normalised);
            if (m.find()) {
                return Optional.of(new Match(e.getKey(), snippet(normalised, m.start(), m.end())));
            }
        }
        return Optional.empty();
    }

    private static String snippet(String text, int start, int end) {
        int from = Math.max(0, start);
        int to = Math.min(text.length(), Math.min(end, start + SNIPPET_MAX));
        String s = text.substring(from, to);
        return end - start > SNIPPET_MAX ? s + "…" : s;
    }
}
