package com.example.copilot.security;

/**
 * Thrown by the ingest-time scanner (Layer 1) when a document's content matches
 * a configured prompt-injection pattern. Carries the matched pattern id (a
 * bounded, non-sensitive label) -- never the document content. Mapped to a 400
 * by {@code IngestController}.
 */
public class InjectionDetectedException extends RuntimeException {

    private final String patternId;

    public InjectionDetectedException(String patternId) {
        super("ingested content matched injection pattern '" + patternId + "'");
        this.patternId = patternId;
    }

    public String patternId() {
        return patternId;
    }
}
