package com.example.copilot.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;
    private final ChatService chatService;

    public ChatController(ChatClient chatClient, ChatService chatService) {
        this.chatClient = chatClient;
        this.chatService = chatService;
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(String reply) {
    }

    /**
     * Blocking call. Routed through {@link ChatService} (a separate bean) so the
     * Resilience4j circuit breaker + fallback-model AOP advice fires -- a
     * throttle/outage on the primary Bedrock model degrades to a fallback
     * answer instead of a 500. On the happy path this is identical to a direct
     * primary call.
     */
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return new ChatResponse(chatService.chat(request.message()));
    }

    /** Token streaming over Server-Sent Events - the basis for the Phase 1 UI. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
