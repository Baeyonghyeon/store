package com.example1.bddllm.slack;

import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "bdd.role", havingValue = "slack")
public class SlackApi {
    public static class Rejected extends RuntimeException {
        public final String code;

        public Rejected(String code) {
            super("slack_api_rejected");
            this.code =
                Set.of(
                        "not_in_channel",
                        "channel_not_found",
                        "missing_scope",
                        "invalid_auth",
                        "token_revoked",
                        "ratelimited",
                        "is_archived")
                    .contains(code)
                    ? code
                    : "slack_api_error";
        }
    }

    private final OkHttpClient client;
    private final String base, token;

    public SlackApi(Environment env) {
        token = env.getProperty("SLACK_BOT_TOKEN", "");
        if (!token.startsWith("xoxb-"))
            throw new IllegalArgumentException("SLACK_BOT_TOKEN을 설정하세요.");
        base = env.getProperty("SLACK_API_BASE_URL", "https://slack.com/api");
        client =
            new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(10))
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    @PreDestroy
    public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    public ObjectNode call(String method, ObjectNode body) {
        Request request =
            new Request.Builder()
                .url(base + "/" + method)
                .header("Authorization", "Bearer " + token)
                .post(
                    RequestBody.create(
                        Json.encode(body), MediaType.get("application/json")))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("slack_http_error");
            JsonNode out = Json.parse(response.body() == null ? "" : response.body().string());
            if (!out.path("ok").asBoolean()) throw new Rejected(out.path("error").asText());
            return (ObjectNode) out;
        } catch (IOException exception) {
            throw new IllegalStateException("slack_delivery_unknown");
        }
    }
}
