package com.example1.bddllm;

import static org.assertj.core.api.Assertions.*;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.analysis.LlmFailure;
import com.example1.bddllm.analysis.SpringAiLlmGateway;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.evaluation.EvaluationService;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.event.Grouping;
import com.example1.bddllm.evidence.EvidenceRegistry;
import com.example1.bddllm.slack.SlackMetadata;
import com.example1.bddllm.worker.ExecutionLocks;
import com.example1.bddllm.worker.JobQueue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

class CompatibilityTests {
    static Stream<JsonNode> events() throws Exception {
        return StreamSupport.stream(
                Json.parse(
                                Files.readString(
                                        Path.of("src/test/resources/compatibility/events.json")))
                        .spliterator(),
                false);
    }

    @ParameterizedTest
    @MethodSource("events")
    void eventHashesMatchFixtures(JsonNode fixture) {
        ErrorEvent errorEvent = ErrorEvent.read(fixture.get("event"));
        assertThat(errorEvent.payloadHash()).isEqualTo(fixture.path("payload_hash").asText());
        assertThat(Grouping.fingerprint(errorEvent)).isEqualTo(fixture.path("fingerprint").asText());
        assertThat(Grouping.signature(errorEvent)).isEqualTo(fixture.get("signature"));
        assertThat(errorEvent.conditionHash()).isEqualTo(fixture.path("condition_hash").asText());
        assertThat(errorEvent.context()).isEqualTo(fixture.get("business_context"));
    }

    @Test
    void systemPromptMatchesFixture() throws Exception {
        assertThat(
                        SpringAiLlmGateway.systemPrompt(
                                Files.readString(Path.of("src/main/resources/prompts/bdd-document-analysis-v1.txt"))))
                .isEqualTo(
                        Files.readString(
                                Path.of("src/test/resources/compatibility/system-prompt.txt")));
    }

    @Test
    void basisHashesMatchFixtures() throws Exception {
        for (JsonNode fixture :
                Json.parse(
                        Files.readString(Path.of("src/test/resources/compatibility/bases.json"))))
            assertThat(AnalysisPolicy.basisHash(fixture.get("basis")))
                    .isEqualTo(fixture.path("basis_hash").asText());
    }

    @Test
    void canonicalEncodingHandlesNumbersUnicodeAndControlCharacters() throws Exception {
        for (JsonNode fixture :
                Json.parse(
                        Files.readString(
                                Path.of("src/test/resources/compatibility/canonical.json")))) {
            assertThat(Json.canonical(fixture.get("value")))
                    .isEqualTo(fixture.path("canonical").asText());
            assertThat(Json.spaced(fixture.get("value")))
                    .isEqualTo(fixture.path("spaced").asText());
            assertThat(Json.pretty(fixture.get("value")))
                    .isEqualTo(fixture.path("pretty").asText());
        }
    }

    @Test
    void basisBuilderMatchesFixture() throws Exception {
        ErrorEvent event =
                ErrorEvent.read(
                        Json.parse(Files.readString(Path.of("samples/coupon_expired/event.json"))));
        Path root = Path.of("samples/coupon_expired").toRealPath();
        ObjectNode spec = EvidenceRegistry.document(root, "specification.md");
        ObjectNode bdd = EvidenceRegistry.document(root, "existing.feature");
        for (JsonNode fixture :
                Json.parse(
                        Files.readString(Path.of("src/test/resources/compatibility/bases.json")))) {
            JsonNode expected = fixture.path("basis");
            assertThat(
                            AnalysisPolicy.basis(
                                    event,
                                    spec,
                                    bdd,
                                    Files.readString(Path.of("src/main/resources/prompts/bdd-document-analysis-v1.txt")),
                                    expected.path("provider").asText(),
                                    expected.path("model").asText()))
                    .isEqualTo(expected);
        }
    }

    @Test
    void strictTypesAndUnknownFields() throws Exception {
        ObjectNode event = (ObjectNode) events().findFirst().orElseThrow().get("event").deepCopy();
        ((ObjectNode) event.get("response")).put("status_code", "500");
        assertThatThrownBy(() -> ErrorEvent.read(event)).isInstanceOf(Problem.class);
        event.put("extra", 1);
        assertThatThrownBy(() -> ErrorEvent.read(event)).isInstanceOf(Problem.class);
    }

    @Test
    void defaultsPreserveLegacyHash() throws Exception {
        ObjectNode event = (ObjectNode) events().findFirst().orElseThrow().get("event").deepCopy();
        event.remove("business_context");
        ErrorEvent normalized = ErrorEvent.read(event);
        event.putNull("business_context");
        assertThat(ErrorEvent.read(event).payloadHash()).isEqualTo(normalized.payloadHash());
    }

    @Test
    void conditionsDistinguishObservedStates() throws Exception {
        ObjectNode event = (ObjectNode) events().findFirst().orElseThrow().get("event").deepCopy();
        event.put("service", "noteu");
        ((ObjectNode) event.get("request"))
                .put("method", "GET")
                .put("route_template", ErrorEvent.CHAT_ROUTE);
        ErrorEvent legacy = ErrorEvent.read(event);
        event.set("business_context", Json.obj());
        assertThat(ErrorEvent.read(event).conditionHash()).isEqualTo(legacy.conditionHash());
        event.set("business_context", Json.obj("auth_state", "anonymous"));
        assertThat(ErrorEvent.read(event).conditionHash()).isNotEqualTo("legacy");
    }

    @Test
    void relativeExecutionLockDirectoriesAreRejected() {
        assertThatThrownBy(() -> new ExecutionLocks("relative-locks"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedRetryPolicy() {
        assertThat(JobQueue.delay(1, 3, "llm_server_error", null)).isEqualTo(30.);
        assertThat(JobQueue.delay(2, 3, "llm_rate_limited", 90.)).isEqualTo(90.);
        assertThat(JobQueue.delay(3, 3, "llm_server_error", null)).isNull();
        assertThat(JobQueue.delay(1, 3, "llm_timeout", null)).isNull();
        assertThat(JobQueue.delay(1, 3, "llm_server_error", 86401.)).isNull();
    }

    @Test
    void rejectUnquotedOrMalformedResults() throws Exception {
        JsonNode referenceResult =
                Json.parse(
                        Files.readString(
                                Path.of("samples/evaluation/coupon_missing/reference.json")));
        assertThatThrownBy(() -> AnalysisPolicy.result(referenceResult, "nothing", "nothing"))
                .isInstanceOf(Problem.class);
        ObjectNode parsedResult = SpringAiLlmGateway.parseContent("```json\n" + Json.encode(referenceResult) + "\n```");
        assertThat(parsedResult).isEqualTo(referenceResult);
        assertThatThrownBy(() -> SpringAiLlmGateway.parseContent("설명\n" + Json.encode(referenceResult)))
                .isInstanceOf(LlmFailure.class);
    }

    @Test
    void wireMustBeCompletedAssistant() {
        ObjectNode providerResponse =
                Json.obj(
                        "choices",
                        List.of(
                                Json.obj(
                                        "finish_reason",
                                        "length",
                                        "message",
                                        Json.obj("role", "assistant", "content", "{}"))));
        assertThatThrownBy(() -> SpringAiLlmGateway.validateChatWire(providerResponse))
                .isInstanceOf(LlmFailure.class);
    }

    @Test
    void distinguishesQuotaAndRateLimit() {
        assertThat(
                        SpringAiLlmGateway.httpFailure(
                                        "ollama",
                                        429,
                                        "{\"error\":{\"code\":\"insufficient_quota\"}}",
                                        null)
                                .code)
                .isEqualTo("llm_quota");
        assertThat(SpringAiLlmGateway.httpFailure("deepseek", 429, "{}", "60").retryAfter)
                .isEqualTo(60.);
    }

    @Test
    void slackMetadataSignaturesRoundTrip() throws Exception {
        String key = "xapp-fixture";
        SlackMetadata metadata = new SlackMetadata(key);
        ObjectNode data =
                Json.obj(
                        "kind",
                        "message",
                        "team_id",
                        "T1",
                        "channel_id",
                        "C1",
                        "job_id",
                        5,
                        "candidate_no",
                        1,
                        "revision",
                        4);
        String encoded =
                Base64.getUrlEncoder()
                        .encodeToString(Json.encode(data).getBytes(StandardCharsets.UTF_8));
        assertThat(metadata.sign(data)).startsWith(encoded + ".");
        assertThat(metadata.verify(metadata.sign(data))).isEqualTo(data);
        assertThatThrownBy(() -> metadata.verify(metadata.sign(data) + "bad"))
                .isInstanceOf(Problem.class);
    }

    @Test
    void fourReferenceCasesPass() throws Exception {
        EvaluationService evaluation = new EvaluationService(null, null);
        List<EvaluationService.Case> cases = evaluation.load("samples/evaluation");
        assertThat(cases).hasSize(4);
        for (EvaluationService.Case evaluationCase : cases)
            assertThat(EvaluationService.score(evaluationCase, evaluationCase.reference()).path("automated_pass").asBoolean())
                    .isTrue();
    }
}
