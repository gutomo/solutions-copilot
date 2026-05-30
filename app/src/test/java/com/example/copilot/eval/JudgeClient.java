package com.example.copilot.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the autoconfigured Bedrock ChatModel that builds per-call
 * options targeting the configurable judge model at temperature 0. Cannot reuse
 * the production ChatClient bean because that one is glued to the QA advisor +
 * tool callbacks and runs at the production temperature (0.3).
 */
@Component
public class JudgeClient {

    private static final Logger log = LoggerFactory.getLogger(JudgeClient.class);

    private final ChatModel chatModel;
    private final String judgeModel;

    public JudgeClient(ChatModel chatModel,
                       @Value("${eval.judge.model}") String judgeModel) {
        this.chatModel = chatModel;
        this.judgeModel = judgeModel;
    }

    public String judgeModelId() {
        return judgeModel;
    }

    public String call(String prompt) {
        ChatOptions options = ChatOptions.builder()
                .model(judgeModel)
                .temperature(0.0)
                .build();
        String verdict = chatModel.call(new Prompt(prompt, options))
                .getResult().getOutput().getText();
        log.debug("[judge] verdict={}", verdict.replace('\n', ' '));
        return verdict == null ? "" : verdict;
    }
}
