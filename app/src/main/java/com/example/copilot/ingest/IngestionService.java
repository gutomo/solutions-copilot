package com.example.copilot.ingest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.copilot.security.InjectionDetectedException;
import com.example.copilot.security.InjectionScanner;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;
    private final InjectionScanner injectionScanner;
    private final MeterRegistry meters;

    public IngestionService(VectorStore vectorStore, InjectionScanner injectionScanner, MeterRegistry meters) {
        this.vectorStore = vectorStore;
        this.injectionScanner = injectionScanner;
        this.meters = meters;
        // Default chunk sizing (~800 tokens / 350 char floor) is a reasonable
        // starting point for Titan v2's 8192-token window. Tune in Phase 3 once
        // the eval harness exists.
        this.splitter = new TokenTextSplitter();
    }

    public IngestResult ingest(String source, String content) {
        // Phase 4 slice 5, Layer 1: reject injection-bearing content BEFORE it is
        // chunked/embedded/stored, so a poisoned doc never enters the retrieval
        // corpus. Reject (not quarantine) is the safe default here.
        Optional<InjectionScanner.Match> hit = injectionScanner.scan(content);
        if (hit.isPresent()) {
            String patternId = hit.get().patternId();
            meters.counter("copilot.security.injection.blocked", "pattern", patternId).increment();
            // Snippet only -- never log the full document content.
            log.warn("[security] injection blocked source={} pattern={} snippet=\"{}\"",
                    source, patternId, hit.get().snippet());
            throw new InjectionDetectedException(patternId);
        }

        Document document = new Document(content, Map.of("source", source));
        List<Document> chunks = splitter.split(document);
        vectorStore.add(chunks);
        return new IngestResult(source, chunks.size());
    }

    public record IngestResult(String source, int chunks) {
    }
}
