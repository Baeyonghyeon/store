package com.example1.bddllm.analysis;

import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;

/**
 * Non-streaming compatibility adapter for the persisted OpenAI Responses contract.
 */
final class OpenAiResponsesChatModel implements ChatModel, AutoCloseable {
    private final AiConnectionProperties properties;
    private final OkHttpClient client;

    OpenAiResponsesChatModel(AiConnectionProperties properties) {
        this.properties = properties;
        this.client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(10))
            .readTimeout(properties.getTimeout()).callTimeout(properties.getTimeout())
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!(prompt instanceof AnalysisPrompt request)) throw new LlmFailure("llm_request");
        ObjectNode body = Json.obj("model", request.getOptions().getModel(), "instructions", request.originalInstructions(), "input", List.of(Json.obj("role", "user", "content", request.getUserMessage().getText())), "max_output_tokens", 6000, "store", false, "text", Json.obj("format", Json.obj("type", "json_schema", "name", "AnalysisResult", "schema", request.schema(), "strict", true)));
        try {
            Request httpRequest = new Request.Builder().url(properties.getBaseUrl().replaceAll("/+$", "") + "/responses").header("Authorization", "Bearer " + properties.getApiKey().strip()).post(RequestBody.create(Json.encode(body), MediaType.get("application/json"))).build();
            String raw;
            try (Response http = client.newCall(httpRequest).execute()) {
                raw = http.body() == null ? "" : http.body().string();
                if (!http.isSuccessful())
                    throw SpringAiLlmGateway.httpFailure("openai", http.code(), raw, http.header("Retry-After"));
            }
            JsonNode response = Json.parse(raw);
            if (!response.path("status").asText().equals("completed") || !response.path("output").isArray())
                throw new LlmFailure("llm_response");
            StringBuilder text = new StringBuilder();
            for (JsonNode item : response.path("output")) {
                if (item.path("type").asText().equals("message")) for (JsonNode contentPart : item.path("content")) {
                    if (!contentPart.path("type").asText().equals("output_text")) throw new LlmFailure("llm_response");
                    text.append(contentPart.path("text").asText());
                }
                else if (!item.path("type").asText().equals("reasoning")) throw new LlmFailure("llm_response");
            }
            if (text.isEmpty()) throw new LlmFailure("llm_response");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text.toString()),
                ChatGenerationMetadata.builder().finishReason("stop").build())));
        } catch (Exception exception) {
            throw SpringAiLlmGateway.classify(exception);
        }
    }

    @Override
    public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
