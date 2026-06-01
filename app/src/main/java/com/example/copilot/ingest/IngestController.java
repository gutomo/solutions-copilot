package com.example.copilot.ingest;

import java.util.Map;

import com.example.copilot.ingest.IngestionService.IngestResult;
import com.example.copilot.security.InjectionDetectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class IngestController {

    private final IngestionService ingestionService;

    public IngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public record IngestRequest(String source, String content) {
    }

    @PostMapping
    public IngestResult ingest(@RequestBody IngestRequest request) {
        return ingestionService.ingest(request.source(), request.content());
    }

    /**
     * Phase 4 slice 5: a document the injection scanner (Layer 1) flagged is
     * rejected with 400 and NOT stored. The reason carries the bounded pattern
     * id only -- never the offending content.
     */
    @ExceptionHandler(InjectionDetectedException.class)
    public ResponseEntity<Map<String, String>> handleInjection(InjectionDetectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "rejected", "reason", "prompt_injection_detected",
                        "pattern", ex.patternId()));
    }
}
