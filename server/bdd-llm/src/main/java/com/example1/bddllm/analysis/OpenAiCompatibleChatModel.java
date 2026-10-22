package com.example1.bddllm.analysis;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Converts portable job options to provider options without losing JSON/reasoning settings.
 */
final class OpenAiCompatibleChatModel implements ChatModel {
    private final ChatModel delegate;
    private final OpenAiChatOptions defaults;

    OpenAiCompatibleChatModel(ChatModel delegate, OpenAiChatOptions defaults) {
        this.delegate = delegate;
        this.defaults = defaults;
    }

    @Override
    public OpenAiChatOptions getOptions() {
        return defaults;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String model = prompt.getOptions() == null || prompt.getOptions().getModel() == null
            ? defaults.getModel() : prompt.getOptions().getModel();
        OpenAiChatOptions options = defaults.mutate().model(model).build();
        return delegate.call(new Prompt(prompt.getInstructions(), options));
    }
}
