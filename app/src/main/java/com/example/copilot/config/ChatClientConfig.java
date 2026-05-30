package com.example.copilot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * Spring AI auto-configures a ChatClient.Builder backed by the Bedrock
     * Converse ChatModel. We give it a default system prompt here; from Phase 2
     * onward this is where retrieval advisors and tool callbacks get registered.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        You are a solutions copilot for a B2B cloud reseller.
                        Answer concisely and ground claims in the provided context.
                        If you do not know something, say so rather than guessing.
                        """)
                .build();
    }
}
