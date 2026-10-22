package com.example1.bddllm.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;

/** Carries the frozen job model and the schema needed by the Responses compatibility adapter. */
final class AnalysisPrompt extends Prompt {
    private final String originalInstructions;
    private final ObjectNode schema;

    AnalysisPrompt(String model, String instructions, String evidence, ObjectNode schema) {
        super(List.of(new SystemMessage(SpringAiLlmGateway.systemPrompt(instructions, schema)),
                new UserMessage(evidence)), ChatOptions.builder().model(model).build());
        this.originalInstructions = instructions;
        this.schema = schema.deepCopy();
    }

    String originalInstructions() { return originalInstructions; }
    ObjectNode schema() { return schema.deepCopy(); }
}
