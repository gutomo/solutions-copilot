package com.example.copilot.config;

import com.example.copilot.tools.ProposalTool;
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
     * RAG call (Phase 1). RoiTool computes margin / ROI / TCO (Phase 2 slice 1);
     * ProposalTool renders a customer-facing .docx from those figures (slice 2).
     * The system prompt forces the model to chain: ROI first, then proposal.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                 VectorStore vectorStore,
                                 RoiTool roiTool,
                                 ProposalTool proposalTool) {
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

                        When asked to draft, write, produce, or generate a
                        proposal, quote, or deal summary for a customer, you
                        MUST chain the tools: first call the reseller margin
                        tool to obtain the figures, then call the proposal
                        tool, passing those exact figures verbatim. Never
                        write the document yourself; never invent or recompute
                        the numbers; never round the margin-tool outputs.
                        Quote the returned file path back to the user.
                        """)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(searchRequest)
                        .build())
                .defaultTools(roiTool, proposalTool)
                .build();
    }
}
