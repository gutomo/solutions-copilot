package com.example.copilot.config;

import com.example.copilot.tools.RoiTool;
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
     * RAG call (Phase 1). The RoiTool is registered via defaultTools so the
     * model can invoke it for any margin / ROI / TCO arithmetic (Phase 2).
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore, RoiTool roiTool) {
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

                        For any margin, ROI, TCO, monthly-margin, total-margin,
                        customer-cost, or annualized-margin arithmetic involving
                        a reseller contract, you MUST call the reseller margin
                        tool and quote its exact returned numbers. Do not
                        estimate, round, or recompute these figures yourself;
                        if you find yourself doing the math in your head, stop
                        and call the tool instead.
                        """)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(searchRequest)
                        .build())
                .defaultTools(roiTool)
                .build();
    }
}
