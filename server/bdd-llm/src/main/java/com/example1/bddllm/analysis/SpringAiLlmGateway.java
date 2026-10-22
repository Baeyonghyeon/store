package com.example1.bddllm.analysis;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SpringAiLlmGateway implements LlmGateway {
    private final Environment env;
    private final AiProviderRegistry providers;

    public SpringAiLlmGateway(AiProviderRegistry providers, Environment env) {
        this.providers = providers;
        this.env = env;
    }

    public void validateConfiguration(String provider) { providers.get(provider).validate(); }

    public ObjectNode generate(String provider, String model, String instructions, String evidence) {
        validateConfiguration(provider);
        ObjectNode schema = evidence.stripLeading().startsWith("{") && Json.parse(evidence).has("test_sources")
                ? TestEvidencePolicy.SCHEMA : AnalysisPolicy.SCHEMA;
        if (schema == TestEvidencePolicy.SCHEMA && TestEvidencePolicy.inputSize(instructions, evidence) > TestEvidencePolicy.INPUT_LIMIT)
            throw new LlmFailure("analysis_input_too_large");
        try {
            ChatModel chat = providers.get(provider).chatModel();
            ChatResponse response = chat.call(new AnalysisPrompt(model, instructions, evidence, schema));
            if (response.getResults().size() != 1 || response.hasToolCalls()
                    || !"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason()))
                throw new LlmFailure("llm_response");
            return generatedContent(response.getResult().getOutput().getText(), schema);
        } catch (Exception error) {
            throw classify(error);
        }
    }

    public static String systemPrompt(String instructions) {
        return systemPrompt(instructions, AnalysisPolicy.SCHEMA);
    }

    public static String systemPrompt(String instructions, ObjectNode schema) {
        ObjectNode example = Json.obj("summary", "분석 결과 요약", "existing_bdd_assessment", "기존 BDD에서 확인한 내용", "candidates", Json.array(), "questions", Json.array());
        if (schema.has("properties") && schema.path("properties").has("test_assessments"))
            example.set("test_assessments", Json.value(List.of(Json.obj("condition", "검토한 조건", "status", "inconclusive",
                    "explanation", "제공된 근거 범위의 판단 한계", "citations", Json.array(), "candidate_no", 0))));
        return instructions + "\n\n" + "Return exactly one JSON object matching the following JSON Schema. Do not" + " include markdown fences or any text outside the JSON object. The following" + " example shows only the output shape, not a conclusion about the evidence:\n" + Json.insertionSpaced(example) + "\nJSON Schema:\n" + Json.insertionSpaced(schema);
    }

    public static void validateChatWire(JsonNode providerResponse) {
        JsonNode choices = providerResponse.path("choices");
        if (!choices.isArray() || choices.size() != 1) throw new LlmFailure("llm_response");
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (!choice.path("finish_reason").asText().equals("stop") || !message.path("role").asText().equals("assistant") || message.hasNonNull("refusal") || message.path("tool_calls").size() > 0 || message.hasNonNull("function_call") || !message.path("content").isTextual() || message.path("content").asText().isBlank())
            throw new LlmFailure("llm_response");
    }

    private ObjectNode generatedContent(String text, ObjectNode schema) {
        AnalysisDiagnostics.write(env.getProperty("bdd.invalid-result-directory", ""),
                env.getProperty("bdd.job-id", Long.class, 0L), "provider-content", text == null ? "" : text);
        return parseContent(text, schema);
    }

    public static ObjectNode parseContent(String text) {
        return parseContent(text, AnalysisPolicy.SCHEMA);
    }

    public static ObjectNode parseContent(String text, ObjectNode schema) {
        if (text == null) throw new LlmFailure("llm_response");
        String content = text.strip();
        Matcher jsonFenceMatcher = Pattern.compile("```(?:json)?[ \\t]*\\r?\\n(.*?)\\r?\\n```", Pattern.DOTALL).matcher(content);
        if (jsonFenceMatcher.matches()) content = jsonFenceMatcher.group(1);
        try {
            JsonNode parsed = Json.parse(content);
            if (!parsed.isObject()) throw Problem.invalid();
            // The worker validates the complete v2 schema AND source quotes together before storage.
            return schema.path("properties").has("test_assessments")
                    ? (ObjectNode) parsed : AnalysisPolicy.result(parsed, null, null);
        } catch (Problem exception) {
            throw new LlmFailure("llm_response");
        }
    }

    static LlmFailure classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LlmFailure llmFailure) return llmFailure;
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException || cause.getClass().getSimpleName().contains("Timeout"))
                return new LlmFailure("llm_timeout");
            if (cause instanceof IOException) return new LlmFailure("llm_connection");
        }
        return new LlmFailure("llm_response");
    }

    public static LlmFailure httpFailure(String provider, int status, String body, String after) {
        Double delay = null;
        try {
            delay = Double.parseDouble(after);
        } catch (Exception ignored) {
            try {
                delay = Math.max(0., Duration.between(Instant.now(), ZonedDateTime.parse(after, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis() / 1000.);
            } catch (Exception ignored2) {
            }
        }
        if (status == 401 || status == 403) return new LlmFailure("llm_auth");
        if (status == 402) return new LlmFailure("llm_quota");
        if (status >= 500) return new LlmFailure("llm_server_error", delay);
        if (status == 429) {
            if (provider.equals("deepseek")) return new LlmFailure("llm_rate_limited", delay);
            JsonNode err;
            try {
                err = Json.parse(body).path("error");
            } catch (Exception exception) {
                return new LlmFailure("llm_error");
            }
            String code = err.path("code").asText(), type = err.path("type").asText();
            Set<String> quota = Set.of("insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded", "billing_hard_limit_reached", "billing_not_active");
            if (quota.contains(code) || quota.contains(type)) return new LlmFailure("llm_quota");
            if (Set.of("rate_limit_exceeded", "slow_down", "resource_unavailable").contains(code) || !err.hasNonNull("code") && type.equals("rate_limit_error"))
                return new LlmFailure("llm_rate_limited", delay);
            return new LlmFailure("llm_error");
        }
        return new LlmFailure("llm_request");
    }
}
