package com.example.copilot.ingest;

import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        // Default chunk sizing (~800 tokens / 350 char floor) is a reasonable
        // starting point for Titan v2's 8192-token window. Tune in Phase 3 once
        // the eval harness exists.
        this.splitter = new TokenTextSplitter();
    }

    public IngestResult ingest(String source, String content) {
        Document document = new Document(content, Map.of("source", source));
        List<Document> chunks = splitter.split(document);
        vectorStore.add(chunks);
        return new IngestResult(source, chunks.size());
    }

    public record IngestResult(String source, int chunks) {
    }
}
