package com.example1.bddllm.analysis;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.evidence.JavaTestExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates selected test evidence, citations, and analysis results.
 */
public final class TestEvidencePolicy {
    public static final int INPUT_LIMIT = 60000;
    public static final String SCOPE_NOTE = "선택한 테스트 근거만 정적으로 검토했습니다. 저장소 전체의 테스트 부재나 실제 실행·통과 여부를 뜻하지 않습니다.";
    public static final ObjectNode SCHEMA = schema();

    private static ObjectNode schema() {
        ObjectNode schema = AnalysisPolicy.SCHEMA.deepCopy();
        ObjectNode assessment = Json.obj("type", "object", "additionalProperties", false,
            "required", List.of("condition", "status", "explanation", "citations", "candidate_no"),
            "properties", Json.obj("condition", Json.obj("type", "string", "minLength", 1),
                "status", Json.obj("type", "string", "enum", List.of("covered", "weak_assertion", "characterization_only", "not_found_in_selected_evidence", "inconclusive")),
                "explanation", Json.obj("type", "string", "minLength", 1),
                "candidate_no", Json.obj("type", "integer", "minimum", 0, "maximum", 5),
                "citations", Json.obj("type", "array", "maxItems", 10, "items", Json.obj("type", "object", "additionalProperties", false,
                    "required", List.of("chunk_id", "quote"), "properties", Json.obj(
                        "chunk_id", Json.obj("type", "string", "minLength", 1), "quote", Json.obj("type", "string", "minLength", 1))))));
        ((ObjectNode) schema.get("properties")).set("test_assessments", Json.obj("type", "array", "minItems", 1, "maxItems", 20, "items", assessment));
        ((ArrayNode) schema.get("required")).add("test_assessments");
        return schema;
    }

    public static ObjectNode input(JsonNode snapshot) {
        ObjectNode input = snapshot.deepCopy();
        input.remove("source_files");
        return input;
    }

    public static String evidence(ErrorEvent event, JsonNode spec, JsonNode bdd, JsonNode snapshot) {
        ObjectNode base = (ObjectNode) Json.parse(AnalysisPolicy.evidence(event, spec, bdd));
        base.set("test_sources", input(snapshot));
        return Json.pretty(base);
    }

    public static int inputSize(String prompt, String evidence) {
        return Json.length(SpringAiLlmGateway.systemPrompt(prompt, SCHEMA)) + Json.length(evidence);
    }

    public static ObjectNode basis(ErrorEvent event, JsonNode spec, JsonNode bdd, String prompt,
                                   String provider, String model, JsonNode snapshot) {
        validateSnapshot(snapshot);
        if (!event.payload().path("deploy_sha").asText().equalsIgnoreCase(snapshot.path("commit_sha").asText())
            || !snapshot.path("repository").equals(snapshot.path("config").path("repository"))) throw Problem.invalid();
        ObjectNode basis = AnalysisPolicy.basis(event, spec, bdd, prompt, provider, model);
        if (inputSize(prompt, evidence(event, spec, bdd, snapshot)) > INPUT_LIMIT)
            throw new Problem(422, "analysis_input_too_large", "전체 분석 입력이 보수적 6만 글자 한도를 넘습니다.");
        basis.put("key_version", "2").put("analysis_version", "2")
            .put("result_schema_sha256", Json.sha(Json.canonical(SCHEMA)))
            .put("test_evidence_sha256", Json.sha(Json.canonical(snapshot)))
            .put("retrieval_version", JavaTestExtractor.VERSION)
            .put("max_input_codepoints", INPUT_LIMIT).put("max_output_tokens", 6000);
        return basis;
    }

    public static ObjectNode summary(JsonNode snapshot) {
        return Json.obj("repository", snapshot.path("repository"), "commit_sha", snapshot.path("commit_sha"),
            "scope_note", SCOPE_NOTE, "discovered_test_count", snapshot.path("discovered_test_count"),
            "selected_test_count", snapshot.path("selected_tests").size(), "excluded_test_count", snapshot.path("excluded_tests").size(),
            "selection_complete", snapshot.path("selection_complete"), "warnings", snapshot.path("warnings"));
    }

    public static void validateSnapshot(JsonNode snapshot) {
        if (!snapshot.isObject() || !snapshot.path("retrieval_version").asText().equals(JavaTestExtractor.VERSION)
            || !snapshot.path("source_files").isArray() || !snapshot.path("chunks").isArray()
            || !snapshot.path("selected_tests").isArray()) throw Problem.invalid();
        Map<String, JsonNode> files = new HashMap<>();
        for (JsonNode file : snapshot.path("source_files")) {
            if (!Json.sha(Json.text(file, "content")).equals(Json.text(file, "sha256"))
                || files.put(Json.text(file, "path"), file) != null) throw Problem.invalid();
        }
        Set<String> ids = new HashSet<>();
        for (JsonNode chunk : snapshot.path("chunks")) {
            JsonNode file = files.get(Json.text(chunk, "path"));
            if (file == null || !ids.add(Json.text(chunk, "id"))) throw Problem.invalid();
            String source = file.path("content").asText(), text = Json.text(chunk, "content");
            int start = JavaTestExtractor.offset(source, (int) Json.integer(chunk, "start_line", 1, Integer.MAX_VALUE),
                (int) Json.integer(chunk, "start_column", 1, Integer.MAX_VALUE));
            int end = JavaTestExtractor.offset(source, (int) Json.integer(chunk, "end_line", 1, Integer.MAX_VALUE),
                (int) Json.integer(chunk, "end_column", 1, Integer.MAX_VALUE)) + 1;
            if (end < start || end > source.length() || !source.substring(start, end).equals(text)
                || !Json.sha(text).equals(chunk.path("sha256").asText())
                || !Json.sha(chunk.path("path").asText() + ":" + start + ":" + end + ":" + text).equals(chunk.path("id").asText()))
                throw Problem.invalid();
        }
        for (JsonNode option : snapshot.path("citation_options")) {
            JsonNode source = null;
            for (JsonNode chunk : snapshot.path("chunks"))
                if (chunk.path("id").equals(option.path("chunk_id"))) source = chunk;
            if (source == null || !source.path("kind").asText().equals("test")
                || !source.path("content").asText().contains(Json.text(option, "quote"))) throw Problem.invalid();
        }
        Set<String> referenced = new HashSet<>();
        for (JsonNode test : snapshot.path("selected_tests"))
            for (JsonNode id : test.path("chunk_ids")) {
                if (!ids.contains(id.asText())) throw Problem.invalid();
                referenced.add(id.asText());
            }
        if (!referenced.equals(ids)) throw Problem.invalid();
    }

    public static ObjectNode result(JsonNode raw, String spec, String bdd, JsonNode snapshot) {
        ObjectNode legacy = raw.deepCopy();
        legacy.remove("test_assessments");
        AnalysisPolicy.result(legacy, spec, bdd);
        JsonNode assessments = raw.path("test_assessments");
        if (!assessments.isArray() || assessments.isEmpty() || assessments.size() > 20) throw Problem.invalid();
        Map<String, JsonNode> chunks = new HashMap<>();
        if (snapshot != null)
            for (JsonNode chunk : snapshot.path("chunks")) chunks.put(chunk.path("id").asText(), chunk);
        Set<Integer> candidateNumbers = new HashSet<>();
        for (JsonNode assessment : assessments) {
            Set<String> fields = Set.of("condition", "status", "explanation", "citations", "candidate_no");
            Json.fields(assessment, fields, fields);
            Json.text(assessment, "condition");
            Json.text(assessment, "explanation");
            String status = Json.text(assessment, "status");
            if (!Set.of("covered", "weak_assertion", "characterization_only", "not_found_in_selected_evidence", "inconclusive").contains(status))
                throw Problem.invalid();
            int candidate = (int) Json.integer(assessment, "candidate_no", 0, raw.path("candidates").size());
            if (candidate > 0 && (!candidateNumbers.add(candidate) || status.equals("covered")))
                throw Problem.invalid();
            JsonNode citations = assessment.path("citations");
            if (!citations.isArray() || citations.size() > 10) throw Problem.invalid();
            boolean testCited = false;
            for (JsonNode citation : citations) {
                Json.fields(citation, Set.of("chunk_id", "quote"), Set.of("chunk_id", "quote"));
                String id = Json.text(citation, "chunk_id"), quote = Json.text(citation, "quote");
                if (snapshot != null) {
                    JsonNode chunk = chunks.get(id);
                    if (chunk == null || !chunk.path("content").asText().contains(quote)) throw Problem.invalid();
                    testCited |= chunk.path("kind").asText().equals("test");
                }
            }
            if (Set.of("covered", "weak_assertion", "characterization_only").contains(status)
                && (citations.isEmpty() || snapshot != null && !testCited)) throw Problem.invalid();
            if (status.equals("not_found_in_selected_evidence") && !citations.isEmpty()) throw Problem.invalid();
            if (status.equals("covered") && snapshot != null) {
                boolean enabled = false;
                for (JsonNode test : snapshot.path("selected_tests"))
                    if (!test.path("disabled").asBoolean()) for (JsonNode citation : citations)
                        for (JsonNode id : test.path("chunk_ids"))
                            if (id.asText().equals(citation.path("chunk_id").asText()) && chunks.get(id.asText()).path("kind").asText().equals("test"))
                                enabled = true;
                if (!enabled) throw Problem.invalid();
            }
        }
        if (candidateNumbers.size() != raw.path("candidates").size()) throw Problem.invalid();
        return raw.deepCopy();
    }
}
