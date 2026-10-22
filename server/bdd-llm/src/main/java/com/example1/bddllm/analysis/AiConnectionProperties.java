package com.example1.bddllm.analysis;

import java.time.Duration;

/**
 * Connection and model properties; secrets deliberately have no generated toString().
 */
public final class AiConnectionProperties {
    private String apiKey = "";
    private String baseUrl = "";
    private Duration timeout = Duration.ofSeconds(60);
    private final Chat chat = new Chat();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String value) {
        apiKey = value;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String value) {
        baseUrl = value;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration value) {
        timeout = value;
    }

    public Chat getChat() {
        return chat;
    }

    public void validate() {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("your_")) throw new LlmFailure("llm_auth");
        if (baseUrl == null || baseUrl.isBlank() || timeout == null || timeout.isZero() || timeout.isNegative())
            throw new LlmFailure("llm_configuration");
    }

    public static final class Chat {
        private String model = "";

        public String getModel() {
            return model;
        }

        public void setModel(String value) {
            model = value;
        }
    }
}
