package com.example1.bddllm.analysis;

import org.springframework.ai.chat.model.ChatModel;

import java.util.function.Supplier;

/**
 * One configured provider. Models are created only when a worker actually needs them.
 */
public final class AiProvider implements AutoCloseable {
    private final String id;
    private final AiConnectionProperties properties;
    private final Supplier<ChatModel> factory;
    private ChatModel model;

    public AiProvider(String id, AiConnectionProperties properties, Supplier<ChatModel> factory) {
        this.id = id;
        this.properties = properties;
        this.factory = factory;
    }

    public String id() {
        return id;
    }

    public String defaultModel() {
        return properties.getChat().getModel().strip();
    }

    public void validate() {
        properties.validate();
    }

    public synchronized ChatModel chatModel() {
        validate();
        if (model == null) model = factory.get();
        return model;
    }

    @Override
    public synchronized void close() throws Exception {
        if (model instanceof AutoCloseable closeable) closeable.close();
    }
}
