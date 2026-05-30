package com.example.copilot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * Spring AI auto-configures a ChatClient.Builder backed by the Bedrock
     * Converse ChatModel. The QuestionAnswerAdvisor turns every call into a
     * RAG call: the user message is embedded, the pgvector store is queried,
     * and the top-K passages are injected as context before the LLM runs.
     * Phase 2 will register tool callbacks here too.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(5)
                .build();

        return builder
                .defaultSystem("""
                        You are a solutions copilot for a B2B cloud reseller.
                        Answer concisely and ground claims in the provided context.
                        If the context does not contain the answer, say so rather
                        than guessing, and cite the source field from the context
                        when you do answer.
                        """)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(searchRequest)
                        .build())
                .build();
    }
}
