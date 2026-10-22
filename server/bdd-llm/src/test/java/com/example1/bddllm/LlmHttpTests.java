package com.example1.bddllm;

import static org.assertj.core.api.Assertions.*;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.analysis.AiConfiguration;
import com.example1.bddllm.analysis.AiProviderRegistry;
import com.example1.bddllm.analysis.LlmFailure;
import com.example1.bddllm.analysis.SpringAiLlmGateway;
import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class LlmHttpTests {
    HttpServer server;
    AnnotationConfigApplicationContext context;
    SpringAiLlmGateway llm;
    ObjectNode reference;
    AtomicInteger calls;
    AtomicReference<String> body, path, response;
    AtomicReference<String> authorization;
    int status;
    boolean disconnect;

    @BeforeEach
    void setup() throws Exception {
        reference =
                (ObjectNode)
                        Json.parse(
                                Files.readString(
                                        Path.of(
                                                "samples/evaluation/coupon_missing/reference.json")));
        calls = new AtomicInteger();
        body = new AtomicReference<>();
        path = new AtomicReference<>();
        response = new AtomicReference<>();
        authorization = new AtomicReference<>();
        status = 200;
        disconnect = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    calls.incrementAndGet();
                    path.set(exchange.getRequestURI().getPath());
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    if (disconnect) {
                        exchange.close();
                        return;
                    }
                    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OLLAMA_API_KEY", "fake-ollama")
                .withProperty("DEEPSEEK_API_KEY", "fake-deepseek")
                .withProperty("OPENAI_API_KEY", "fake-openai")
                .withProperty("OLLAMA_BASE_URL", base)
                .withProperty("DEEPSEEK_BASE_URL", base)
                .withProperty("OPENAI_BASE_URL", base);
        environment.getPropertySources().addLast(new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml")).getFirst());
        context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(AiConfiguration.class, AiProviderRegistry.class, SpringAiLlmGateway.class);
        context.refresh();
        llm = context.getBean(SpringAiLlmGateway.class);
        chatResponse("stop", "assistant", null);
    }

    void chatResponse(String finish, String role, String refusal) {
        response.set(
                Json.encode(
                        Json.obj(
                                "id",
                                "chatcmpl-test",
                                "object",
                                "chat.completion",
                                "created",
                                1,
                                "model",
                                "fixture",
                                "choices",
                                List.of(
                                        Json.obj(
                                                "index",
                                                0,
                                                "finish_reason",
                                                finish,
                                                "message",
                                                Json.obj(
                                                        "role",
                                                        role,
                                                        "content",
                                                        Json.encode(reference),
                                                        "refusal",
                                                        refusal))),
                                "usage",
                                Json.obj(
                                        "prompt_tokens",
                                        1,
                                        "completion_tokens",
                                        1,
                                        "total_tokens",
                                        2))));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        server.stop(0);
    }

    @Test
    void ollamaUsesSpringAiWithLegacyParameters() {
        assertThat(llm.generate("ollama", "gemma4:31b", "instructions", "evidence"))
                .isEqualTo(reference);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(path.get()).isEqualTo("/v1/chat/completions");
        JsonNode request = Json.parse(body.get());
        assertThat(request.path("max_tokens").asInt()).isEqualTo(6000);
        assertThat(request.path("reasoning_effort").asText()).isEqualTo("none");
        assertThat(request.has("response_format")).isFalse();
        assertThat(request.path("messages").get(0).path("content").asText())
                .startsWith("instructions");
    }

    @Test
    void deepseekDisablesThinkingAndUsesJsonMode() {
        assertThat(llm.generate("deepseek", "deepseek-flash", "instructions", "evidence"))
                .isEqualTo(reference);
        JsonNode request = Json.parse(body.get());
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    void serverFailureIsCalledOnce() {
        status = 503;
        response.set("{\"error\":{\"message\":\"do not expose\"}}");
        assertThatThrownBy(() -> llm.generate("ollama", "fixture", "i", "e"))
                .isInstanceOf(LlmFailure.class)
                .extracting("code")
                .isEqualTo("llm_server_error");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void refusesTruncatedResponse() {
        chatResponse("length", "assistant", null);
        assertThatThrownBy(() -> llm.generate("ollama", "fixture", "i", "e"))
                .isInstanceOf(LlmFailure.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void refusesRefusalEvenIfContentLooksValid() {
        chatResponse("stop", "assistant", "refused");
        assertThatThrownBy(() -> llm.generate("ollama", "fixture", "i", "e"))
                .isInstanceOf(LlmFailure.class);
    }

    @Test
    void refusesWrongRole() {
        chatResponse("stop", "user", null);
        assertThatThrownBy(() -> llm.generate("ollama", "fixture", "i", "e"))
                .isInstanceOf(LlmFailure.class);
    }

    @Test
    void ambiguousOllamaDeliveryIsNotRetried() {
        disconnect = true;
        assertThatThrownBy(() -> llm.generate("ollama", "fixture", "i", "e"))
                .isInstanceOf(LlmFailure.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void ambiguousOpenAiDeliveryIsNotRetried() {
        disconnect = true;
        assertThatThrownBy(() -> llm.generate("openai", "fixture", "i", "e"))
                .isInstanceOf(LlmFailure.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void preservesOpenAiResponsesContract() {
        response.set(
                Json.encode(
                        Json.obj(
                                "status",
                                "completed",
                                "output",
                                List.of(
                                        Json.obj(
                                                "type",
                                                "message",
                                                "content",
                                                List.of(
                                                        Json.obj(
                                                                "type",
                                                                "output_text",
                                                                "text",
                                                                Json.encode(reference))))))));
        assertThat(llm.generate("openai", "fixture", "instructions", "evidence"))
                .isEqualTo(reference);
        JsonNode request = Json.parse(body.get());
        assertThat(path.get()).isEqualTo("/v1/responses");
        assertThat(request.path("store").asBoolean(true)).isFalse();
        assertThat(request.path("text").path("format").path("schema"))
                .isEqualTo(AnalysisPolicy.SCHEMA);
    }
    @Test
    void testEvidenceUsesVersionedSchemaForOllamaAndDeepSeek() {
        reference.set("test_assessments", Json.value(List.of(Json.obj("condition", "쿠폰 만료", "status", "not_found_in_selected_evidence",
                "explanation", "선택한 근거에서 확인하지 못했습니다.", "citations", Json.array(), "candidate_no", 1))));
        chatResponse("stop", "assistant", null);
        for (String provider : List.of("ollama", "deepseek")) {
            assertThat(llm.generate(provider, "fixture", "instructions", "{\"test_sources\":{}}"))
                    .isEqualTo(reference);
            JsonNode request = Json.parse(body.get());
            assertThat(request.path("messages").get(0).path("content").asText())
                    .contains("test_assessments", "characterization_only", "chunk_id");
            assertThat(request.path("max_tokens").asInt()).isEqualTo(6000);
        }
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void testEvidenceUsesVersionedSchemaForResponses() {
        reference.set("test_assessments", Json.value(List.of(Json.obj("condition", "쿠폰 만료", "status", "not_found_in_selected_evidence",
                "explanation", "선택한 근거에서 확인하지 못했습니다.", "citations", Json.array(), "candidate_no", 1))));
        response.set(Json.encode(Json.obj("status", "completed", "output", List.of(Json.obj("type", "message",
                "content", List.of(Json.obj("type", "output_text", "text", Json.encode(reference))))))));
        llm.generate("openai", "fixture", "instructions", "{\"test_sources\":{}}");
        JsonNode request = Json.parse(body.get());
        assertThat(request.path("text").path("format").path("schema"))
                .isEqualTo(com.example1.bddllm.analysis.TestEvidencePolicy.SCHEMA);
        assertThat(request.path("store").asBoolean(true)).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void oversizedCompleteSystemAndUserInputNeverCallsProvider() {
        assertThatThrownBy(() -> llm.generate("ollama", "fixture", "x".repeat(60000), "{\"test_sources\":{}}"))
                .isInstanceOf(LlmFailure.class).extracting("code").isEqualTo("analysis_input_too_large");
        assertThat(calls.get()).isZero();
    }

    @Test
    void legacyResponseParserRejectsNewFieldsWhileVersionedParserAcceptsThem() {
        reference.set("test_assessments", Json.value(List.of(Json.obj("condition", "쿠폰 만료", "status", "not_found_in_selected_evidence",
                "explanation", "선택한 근거에서 확인하지 못했습니다.", "citations", Json.array(), "candidate_no", 1))));
        assertThatThrownBy(() -> SpringAiLlmGateway.parseContent(Json.encode(reference))).isInstanceOf(LlmFailure.class);
        assertThat(SpringAiLlmGateway.parseContent(Json.encode(reference), com.example1.bddllm.analysis.TestEvidencePolicy.SCHEMA))
                .isEqualTo(reference);
    }

    @Test
    void sharedModelsUseEachJobsFrozenModelAndIsolatedProviderCredentials() {
        AiProviderRegistry registry = context.getBean(AiProviderRegistry.class);
        assertThat(registry.get("ollama").chatModel()).isSameAs(registry.get("ollama").chatModel());
        for (String provider : List.of("ollama", "deepseek")) {
            llm.generate(provider, "job-original-model", "instructions", "evidence");
            assertThat(Json.parse(body.get()).path("model").asText()).isEqualTo("job-original-model");
            assertThat(authorization.get()).isEqualTo("Bearer fake-" + provider);
            llm.generate(provider, "job-different-model", "instructions", "evidence");
            assertThat(Json.parse(body.get()).path("model").asText()).isEqualTo("job-different-model");
        }
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void rateLimitDoesNotCauseSdkRetryForAnyProvider() {
        status = 429;
        response.set("{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        for (String provider : List.of("ollama", "deepseek", "openai")) {
            assertThatThrownBy(() -> llm.generate(provider, "fixture", "i", "e"))
                    .isInstanceOf(LlmFailure.class).extracting("code").isEqualTo("llm_rate_limited");
        }
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void ambiguousDeepSeekDeliveryIsNotRetried() {
        disconnect = true;
        assertThatThrownBy(() -> llm.generate("deepseek", "fixture", "i", "e"))
                .isInstanceOf(LlmFailure.class);
        assertThat(calls.get()).isEqualTo(1);
    }

}
