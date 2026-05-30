package com.example.copilot.ingest;

import com.example.copilot.ingest.IngestionService.IngestResult;
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
}
