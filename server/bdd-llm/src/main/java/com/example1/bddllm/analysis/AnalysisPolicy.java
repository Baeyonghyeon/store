package com.example1.bddllm.analysis;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public final class AnalysisPolicy {
    public static final ObjectNode SCHEMA;
    public static final String CONTEXT_NOTES =
        "business_context는 오류 발생 요청에서 관측한 상태입니다. unknown은 미확인이며 없음·비수강을 뜻하지 않습니다. 이 조건에서의 시나리오를"
            + " 명세와 비교하고, 조건 정보만으로 기대 정책이나 다른 업무 조건의 실패를 확정하지 마세요.";

    static {
        try (InputStream in = new ClassPathResource("analysis-schema.json").getInputStream()) {
            SCHEMA = (ObjectNode) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException schemaLoadException) {
            throw new ExceptionInInitializerError(schemaLoadException);
        }
    }

    public static String evidence(ErrorEvent errorEvent, JsonNode spec, JsonNode bdd) {
        ObjectNode event = errorEvent.payload().deepCopy();
        event.remove("event_id");
        ObjectNode analysisEvidence =
            Json.obj(
                "event",
                event,
                "specification",
                spec.path("content"),
                "bdd",
                bdd.path("content"));
        if (event.has("business_context")) analysisEvidence.put("business_context_notes", CONTEXT_NOTES);
        return Json.pretty(analysisEvidence);
    }

    public static ObjectNode basis(
        ErrorEvent errorEvent,
        JsonNode spec,
        JsonNode bdd,
        String prompt,
        String provider,
        String model) {
        if (prompt.isBlank()
            || model.isBlank()
            || !Set.of("openai", "ollama", "deepseek").contains(provider))
            throw Problem.invalid();
        for (JsonNode document : List.of(spec, bdd))
            if (!Json.sha(Json.text(document, "content")).equals(document.path("sha256").asText()))
                throw Problem.invalid();
        if (Json.length(prompt) + Json.length(evidence(errorEvent, spec, bdd)) > 60000)
            throw new Problem(422, "analysis_registration_failed", "분석 자료가 너무 큽니다.");
        return Json.obj(
            "key_version",
            "1",
            "provider",
            provider,
            "deploy_sha",
            errorEvent.payload().path("deploy_sha"),
            "specification_sha256",
            spec.path("sha256"),
            "bdd_sha256",
            bdd.path("sha256"),
            "model",
            model.strip(),
            "prompt_sha256",
            Json.sha(prompt),
            "result_schema_sha256",
            Json.sha(Json.canonical(SCHEMA)),
            "analysis_version",
            "1",
            "max_output_tokens",
            6000);
    }

    public static String basisHash(JsonNode basis) {
        ObjectNode copy = basis.deepCopy();
        if (copy.path("provider").asText("openai").equals("openai")) copy.remove("provider");
        return Json.sha(Json.canonical(copy));
    }

    public static void candidate(JsonNode candidate, String spec, String bdd) {
        Set<String> keys =
            Set.of(
                "title",
                "gap_type",
                "reason",
                "existing_bdd_quotes",
                "given",
                "when",
                "then");
        Json.fields(candidate, keys, keys);
        for (String fieldName : List.of("title", "gap_type", "reason", "when")) Json.text(candidate, fieldName);
        if (!Set.of("missing_scenario", "weak_assertion").contains(candidate.path("gap_type").asText()))
            throw Problem.invalid();
        strings(candidate.path("given"), true);
        strings(candidate.path("existing_bdd_quotes"), false);
        if (!candidate.path("then").isArray() || candidate.path("then").isEmpty()) throw Problem.invalid();
        for (JsonNode expectedBehavior : candidate.path("then")) {
            Json.fields(
                expectedBehavior,
                Set.of("behavior", "specification_quote"),
                Set.of("behavior", "specification_quote"));
            Json.text(expectedBehavior, "behavior");
            String quote = Json.text(expectedBehavior, "specification_quote");
            if (spec != null && !spec.contains(quote)) throw Problem.invalid();
        }
        if (bdd != null)
            for (JsonNode bddQuote : candidate.path("existing_bdd_quotes"))
                if (!bdd.contains(bddQuote.asText())) throw Problem.invalid();
    }

    public static ObjectNode result(JsonNode result, String spec, String bdd) {
        Set<String> keys = Set.of("summary", "existing_bdd_assessment", "candidates", "questions");
        Json.fields(result, keys, keys);
        Json.text(result, "summary");
        Json.text(result, "existing_bdd_assessment");
        strings(result.path("questions"), false);
        JsonNode candidates = result.path("candidates");
        if (!candidates.isArray() || candidates.size() > 5) throw Problem.invalid();
        for (JsonNode candidate : candidates) candidate(candidate, spec, bdd);
        return result.deepCopy();
    }

    private static void strings(JsonNode values, boolean nonempty) {
        if (!values.isArray() || nonempty && values.isEmpty()) throw Problem.invalid();
        for (JsonNode textValue : values) if (!textValue.isTextual() || Json.blank(textValue.asText())) throw Problem.invalid();
    }

    public static void scenario(StringBuilder out, JsonNode candidate) {
        out.append("\n  Scenario: ").append(Json.oneLine(candidate.path("title").asText())).append('\n');
        int stepIndex = 0;
        for (JsonNode precondition : candidate.path("given"))
            out.append("    ")
                .append(stepIndex++ == 0 ? "Given" : "And")
                .append(' ')
                .append(Json.oneLine(precondition.asText()))
                .append('\n');
        out.append("    When ").append(Json.oneLine(candidate.path("when").asText())).append('\n');
        stepIndex = 0;
        for (JsonNode expectedBehavior : candidate.path("then"))
            out.append("    ")
                .append(stepIndex++ == 0 ? "Then" : "And")
                .append(' ')
                .append(Json.oneLine(expectedBehavior.path("behavior").asText()))
                .append('\n');
    }

    public static String feature(JsonNode analysisResult) {
        StringBuilder out =
            new StringBuilder(
                "# AI가 제안한 검토용 후보입니다. 아직 실행된 테스트가 아닙니다.\n"
                    + "Feature: 운영 오류에서 발견한 BDD 시나리오 후보\n");
        for (JsonNode candidate : analysisResult.path("candidates")) scenario(out, candidate);
        if (analysisResult.path("candidates").isEmpty())
            out.append("  # 제안된 후보가 없습니다. JSON의 비교 결과와 질문을 확인하세요.\n");
        return out.toString();
    }
}
