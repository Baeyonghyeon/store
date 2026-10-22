package com.example1.bddllm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.analysis.TestEvidencePolicy;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.evidence.*;
import com.example1.bddllm.slack.SlackViews;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class TestSourceTests {
    static String testPrompt() {
        try {
            return Files.readString(Path.of("src/main/resources/prompts/bdd-test-analysis-v1.txt"));
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    static final String SHA = "a".repeat(40);
    static final String PATH = "src/test/java/ChatTest.java";
    static final String CODE = """
        import org.junit.jupiter.api.*;
        import static org.junit.jupiter.api.Assertions.*;
        class ChatTest {
            private int user;
            @BeforeEach void setup() { user = helper(); }
            @AfterEach void cleanup() { user = 0; }
            int helper() { return 1; }
            @Test void anonymousReturnsCurrentError() { assertEquals(500, request()); }
            @Test void authenticatedWorks() { assertEquals(200, user); }
            int request() { return 500; }
        }
        """;
    static ObjectNode document(String path, String content, String kind) {
        return Json.obj("path", path, "content", content, "sha256", Json.sha(content), "kind", kind, "status", "collected");
    }
    static ArrayNode documents(String code) { ArrayNode array = Json.array(); array.add(document(PATH, code, "test")); return array; }
    static TestSourceConfig config() { return new TestSourceConfig("owner/repo", List.of(PATH), List.of(), List.of("ChatRoomController"), List.of("anonymous")); }
    static ErrorEvent event() throws Exception {
        ObjectNode event = (ObjectNode) Json.parse(Files.readString(Path.of("samples/coupon_expired/event.json")));
        event.put("deploy_sha", SHA);
        return ErrorEvent.read(event);
    }
    static ObjectNode docs() { return Json.obj("specification", Json.obj("content", "비로그인은 로그인 페이지로 이동한다.", "sha256", Json.sha("비로그인은 로그인 페이지로 이동한다.")),
            "bdd", Json.obj("content", "기존 정상 요청", "sha256", Json.sha("기존 정상 요청"))); }
    static ObjectNode selected(String code) throws Exception {
        return TestEvidenceService.select(event(), docs(), testPrompt(), config(), documents(code));
    }
    static ObjectNode assessmentResult(ObjectNode snapshot, String status) {
        JsonNode chunk = java.util.stream.StreamSupport.stream(snapshot.path("chunks").spliterator(), false)
                .filter(candidateChunk -> candidateChunk.path("kind").asText().equals("test")).findFirst().orElseThrow();
        ArrayNode citations = Json.array(); citations.add(Json.obj("chunk_id", chunk.path("id"), "quote", "assertEquals(500, request());"));
        return Json.obj("summary", "관측된 500 응답", "existing_bdd_assessment", "선택한 근거 범위의 분석", "candidates", Json.array(), "questions", Json.array(),
                "test_assessments", List.of(Json.obj("condition", "비로그인", "status", status,
                        "explanation", "500 응답 확인은 로그인 이동 보장이 아닙니다.", "citations", citations, "candidate_no", 0)));
    }
    @Test void configRejectsTraversalDuplicatesAndNonJavaFiles() {
        for (String path : List.of("../Test.java", "/Test.java", "a/./T.java", "a//T.java", "test.txt", "a/%2e%2e/T.java"))
            assertThatThrownBy(() -> new TestSourceConfig("o/r", List.of(path), List.of(), List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSourceConfig("o/r", List.of(PATH), List.of(PATH), List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void bundlesKeepSetupCleanupAndTransitiveHelpers() {
        JavaTestExtractor.Extraction extraction = new JavaTestExtractor().extract(documents(CODE));
        assertThat(extraction.bundles()).hasSize(2);
        String first = extraction.bundles().get(0).path("chunks").toString();
        assertThat(first).contains("setup()", "cleanup()", "helper()", "request()", "private int user", "assertEquals(500");
        assertThat(first).doesNotContain("authenticatedWorks()");
    }
    @Test void actualNoteuFilesExtractAllTestsAndPreserveOriginalRanges() throws Exception {
        ArrayNode documents = Json.array();
        for (String name : List.of("ChatRoomBusinessContextTest.java", "ChatRoomServiceObservationTest.java"))
            documents.add(document("src/test/java/" + name, Files.readString(Path.of("src/test/resources/test-sources/" + name)), "test"));
        TestSourceConfig config = new TestSourceConfig("owner/repo", List.of("src/test/java/ChatRoomBusinessContextTest.java", "src/test/java/ChatRoomServiceObservationTest.java"), List.of(), List.of(), List.of());
        ObjectNode snapshot = TestEvidenceService.select(event(), docs(), "i", config, documents);
        assertThat(snapshot.path("selected_tests").size() + snapshot.path("excluded_tests").size()).isEqualTo(snapshot.path("discovered_test_count").asInt());
        assertThat(snapshot.path("discovered_test_count").asInt()).isGreaterThanOrEqualTo(7);
        assertThat(snapshot.path("chunks").toString()).contains("authenticate", "onlyEvent", "BeforeEach", "isInternalServerError");
        TestEvidencePolicy.validateSnapshot(snapshot);
    }
    @Test void originalWindowsNewlinesTabsAndUnicodeRangesAreRetained() throws Exception {
        String code = CODE.replace("private int user;", "private String 한글 = \"😀\";\n\tprivate int user;").replace("\n", "\r\n");
        TestEvidencePolicy.validateSnapshot(selected(code));
    }
    @Test void malformedSourceIsExplicitlyIncomplete() throws Exception {
        ObjectNode snapshot = selected("class Broken { @Test void invalid(");
        assertThat(snapshot.path("selected_tests")).isEmpty();
        assertThat(snapshot.path("warnings").toString()).contains("parse_failed");
        assertThat(snapshot.path("selection_complete").asBoolean()).isFalse();
    }
    @Test void explicitSupportFileIsIncludedWithOriginalHash() throws Exception {
        ArrayNode documents = documents(CODE);
        documents.add(document("src/test/java/Fixture.java", "class Fixture { static int value() { return 1; } }", "support"));
        TestSourceConfig config = new TestSourceConfig("o/r", List.of(PATH), List.of("src/test/java/Fixture.java"), List.of(), List.of());
        ObjectNode snapshot = TestEvidenceService.select(event(), docs(), "i", config, documents);
        assertThat(snapshot.path("chunks").toString()).contains("support_file", "Fixture");
        TestEvidencePolicy.validateSnapshot(snapshot);
    }
    @Test void budgetExcludesWholeTestWithoutTruncation() throws Exception {
        String code = CODE.replace("assertEquals(200, user);", "String huge = \"" + "x".repeat(70000) + "\"; assertEquals(200, user);");
        ObjectNode snapshot = selected(code);
        assertThat(snapshot.path("selected_tests")).hasSize(1);
        assertThat(snapshot.path("excluded_tests")).hasSize(1);
        assertThat(snapshot.path("chunks").toString()).contains("assertEquals(500, request());").doesNotContain("String huge");
        String prompt = testPrompt();
        assertThat(TestEvidencePolicy.inputSize(prompt, TestEvidencePolicy.evidence(event(), docs().path("specification"), docs().path("bdd"), snapshot))).isLessThanOrEqualTo(60000);
    }
    @Test void oversizedBaseInputIsRejectedBeforeLlm() throws Exception {
        ErrorEvent event = event();
        assertThatThrownBy(() -> TestEvidenceService.select(event, docs(), "x".repeat(60000), config(), documents(CODE)))
                .isInstanceOf(Problem.class).extracting("code").isEqualTo("analysis_input_too_large");
    }
    @Test void retrievalIsDeterministicAndDoesNotSendUnselectedRawFiles() throws Exception {
        ObjectNode first = selected(CODE), second = selected(CODE);
        assertThat(first).isEqualTo(second);
        assertThat(TestEvidencePolicy.input(first).has("source_files")).isFalse();
        assertThat(first.has("source_files")).isTrue();
        ObjectNode input = docs(); ErrorEvent event = event();
        String prompt = "i";
        ObjectNode originalBasis = TestEvidencePolicy.basis(event, input.path("specification"), input.path("bdd"), prompt, "ollama", "fixture", first);
        ObjectNode changed = selected(CODE.replace("return 500", "return 501"));
        ObjectNode modifiedBasis = TestEvidencePolicy.basis(event, input.path("specification"), input.path("bdd"), prompt, "ollama", "fixture", changed);
        assertThat(AnalysisPolicy.basisHash(originalBasis)).isNotEqualTo(AnalysisPolicy.basisHash(modifiedBasis));
    }
    @Test void characterizationQuoteIsValidatedAndInventedQuoteRejected() throws Exception {
        ObjectNode snapshot = selected(CODE), result = assessmentResult(snapshot, "characterization_only");
        assertThat(TestEvidencePolicy.result(result, "spec", "bdd", snapshot)).isEqualTo(result);
        ((ObjectNode) result.path("test_assessments").get(0).path("citations").get(0)).put("quote", "status().is3xxRedirection()");
        assertThatThrownBy(() -> TestEvidencePolicy.result(result, "spec", "bdd", snapshot)).isInstanceOf(Problem.class);
    }
    @Test void tamperedCodeOrLocationIsRejected() throws Exception {
        ObjectNode snapshot = selected(CODE);
        ((ObjectNode) snapshot.path("chunks").get(0)).put("start_line", 999);
        assertThatThrownBy(() -> TestEvidencePolicy.validateSnapshot(snapshot)).isInstanceOf(RuntimeException.class);
    }
    @Test void disabledTestCannotBeCoveredEvidence() throws Exception {
        ObjectNode snapshot = selected(CODE.replace("@Test void anonymous", "@Disabled @Test void anonymous"));
        ObjectNode result = assessmentResult(snapshot, "covered");
        assertThatThrownBy(() -> TestEvidencePolicy.result(result, "spec", "bdd", snapshot)).isInstanceOf(Problem.class);
    }
    @Test void githubUsesExactCommitAndNoAuthorizationHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>(); AtomicReference<String> authorization = new AtomicReference<>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString()); authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ObjectNode response = exchange.getRequestURI().getPath().contains("/commits/") ? Json.obj("sha", SHA)
                    : Json.obj("type", "file", "path", PATH, "encoding", "base64", "content", Base64.getEncoder().encodeToString(CODE.getBytes(StandardCharsets.UTF_8)));
            byte[] bytes = Json.encode(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            GitHubSourceClient client = new GitHubSourceClient("http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(client.fetch(config(), SHA).get(0).path("content").asText()).isEqualTo(CODE);
            assertThat(paths).containsExactly("/repos/owner/repo/commits/" + SHA, "/repos/owner/repo/contents/" + PATH + "?ref=" + SHA);
            assertThat(authorization.get()).isNull();
        } finally { server.stop(0); }
    }
    @Test void githubErrorsDoNotFallBackToLatestOrCacheFailures() throws Exception {
        for (int status : List.of(422, 404, 429, 403, 500)) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger calls = new AtomicInteger();
            server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(status, -1); exchange.close(); });
            server.start();
            try {
                GitHubSourceClient client = new GitHubSourceClient("http://127.0.0.1:" + server.getAddress().getPort());
                assertThatThrownBy(() -> client.fetch(config(), SHA)).isInstanceOf(Problem.class)
                        .extracting("code").isEqualTo(switch(status) { case 422,404 -> "source_commit_not_found"; case 429 -> "source_rate_limited"; case 403 -> "source_access_denied"; default -> "source_unavailable"; });
                assertThat(calls.get()).isEqualTo(1);
            } finally { server.stop(0); }
        }
    }
    @Test void sourceCacheSerializesConcurrentCallsAndReusesSuccessfulSnapshot() throws Exception {
        SourceSnapshotRepository repository = mock(SourceSnapshotRepository.class); GitHubSourceClient github = mock(GitHubSourceClient.class);
        AtomicReference<SourceSnapshot> stored = new AtomicReference<>();
        when(repository.findById(anyString())).thenAnswer(call -> java.util.Optional.ofNullable(stored.get()));
        when(repository.insertIfAbsent(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            SourceSnapshot snapshot = new SourceSnapshot(); snapshot.cacheKey = call.getArgument(0); snapshot.repository = call.getArgument(1);
            snapshot.commitSha = call.getArgument(2); snapshot.documents = call.getArgument(3); stored.set(snapshot); return 1;
        });
        when(github.fetch(any(), anyString())).thenReturn(documents(CODE));
        SourceCollectionService service = new SourceCollectionService(repository, github);
        try (java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<ArrayNode>> futures = new ArrayList<>();
            for (int requestIndex = 0; requestIndex < 16; requestIndex++) futures.add(pool.submit(() -> service.collect(config(), SHA)));
            for (java.util.concurrent.Future<ArrayNode> future : futures) assertThat(future.get()).hasSize(1);
        }
        verify(github, times(1)).fetch(any(), anyString());
    }
    @Test void slackEvidenceIncludesTestScopeAndCodeWithoutMarkupExecution() throws Exception {
        ObjectNode snapshot = selected(CODE);
        ObjectNode detail = Json.obj("job_id", 1, "summary", "요약", "specification", "명세", "bdd", "BDD",
                "test_evidence", TestEvidencePolicy.input(snapshot), "test_assessments", assessmentResult(snapshot, "characterization_only").path("test_assessments"));
        assertThat(SlackViews.modal(detail, null, "evidence", "").toString()).contains("선택", "ChatTest.java", "assertEquals", "characterization_only");
        assertThat(SlackViews.message(detail, null, "").toString()).contains("테스트 근거", "선택한 테스트 근거");
    }
    @Test void routeMappingSelectsGithubOnlyForConfiguredNoteuApi() throws Exception {
        EvidenceRegistry registry = new EvidenceRegistry("config/evidence.toml", "evidence");
        assertThat(registry.forEvent(event()).has("test_source_config")).isFalse();
        ObjectNode input = event().payload().deepCopy(); input.put("service", "noteu");
        ((ObjectNode) input.get("request")).put("method", "GET").put("route_template", ErrorEvent.CHAT_ROUTE);
        JsonNode config = registry.forEvent(ErrorEvent.read(input)).path("test_source_config");
        assertThat(config.path("repository").asText()).isEqualTo("Baeyonghyeon/noteu");
        assertThat(config.path("test_files")).hasSize(2);
    }

    @Test void parameterizedMethodSourcesAndCustomInheritanceAreExplicit() throws Exception {
        String code = """
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.MethodSource;
                class Params extends BaseFixture {
                    static String[] inputs() { return new String[]{"anonymous"}; }
                    @ParameterizedTest @MethodSource("inputs") void checks(String input) { assert input != null; }
                }
                """;
        ObjectNode snapshot = selected(code);
        assertThat(snapshot.path("chunks").toString()).contains("inputs()", "ParameterizedTest");
        assertThat(snapshot.path("selected_tests").get(0).path("issues").toString()).contains("상속된");
    }

    @Test void missingFileAndRateLimitRemainDifferentFailures() throws Exception {
        for (boolean limited : List.of(false, true)) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger calls = new AtomicInteger();
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                if (exchange.getRequestURI().getPath().contains("/commits/")) {
                    byte[] raw = Json.encode(Json.obj("sha", SHA)).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, raw.length); exchange.getResponseBody().write(raw);
                } else {
                    if (limited) exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
                    exchange.sendResponseHeaders(limited ? 403 : 404, -1);
                }
                exchange.close();
            });
            server.start();
            try {
                GitHubSourceClient github = new GitHubSourceClient("http://127.0.0.1:" + server.getAddress().getPort());
                assertThatThrownBy(() -> github.fetch(config(), SHA)).isInstanceOf(Problem.class)
                        .extracting("code").isEqualTo(limited ? "source_rate_limited" : "source_file_not_found");
                assertThat(calls.get()).isEqualTo(2);
            } finally { server.stop(0); }
        }
    }

    @Test void invalidResultDiagnosticsAreOptInAndSeparateFromStoredSuccess() throws Exception {
        Path directory = Files.createTempDirectory("bdd-invalid-result-");
        ObjectNode invalid = Json.obj("summary", "unsupported output");
        com.example1.bddllm.worker.AnalysisWorker.captureInvalidResult("", 21, invalid);
        assertThat(directory.resolve("job-21-invalid-result.json")).doesNotExist();
        com.example1.bddllm.worker.AnalysisWorker.captureInvalidResult(directory.toString(), 21, invalid);
        assertThat(Json.parse(Files.readString(directory.resolve("job-21-invalid-result.json")))).isEqualTo(invalid);
    }

    @Test void suggestedCitationsAreLiteralSingleLinesAndCannotMergeAssertions() throws Exception {
        String source = """
                class ChatTest {
                    @Test void anonymous() {
                        mvc.perform(get(API))
                            .andExpect(status().isInternalServerError())
                            .andExpect(content().string("existing-error-response"));
                    }
                }
                """;
        ObjectNode snapshot = selected(source);
        assertThat(snapshot.path("citation_options")).hasSize(2);
        JsonNode option = snapshot.path("citation_options").get(0);
        ObjectNode result = Json.obj("summary", "요약", "existing_bdd_assessment", "분석", "candidates", Json.array(), "questions", Json.array(),
                "test_assessments", List.of(Json.obj("condition", "비로그인", "status", "characterization_only", "explanation", "500 확인은 로그인 이동 보장이 아님",
                        "candidate_no", 0, "citations", List.of(option))));
        assertThat(TestEvidencePolicy.result(result, "spec", "bdd", snapshot)).isEqualTo(result);
        ((ObjectNode) result.path("test_assessments").get(0).path("citations").get(0)).put("quote",
                ".andExpect(status().isInternalServerError()).andExpect(content().string(\"existing-error-response\"));");
        assertThatThrownBy(() -> TestEvidencePolicy.result(result, "spec", "bdd", snapshot)).isInstanceOf(Problem.class);
    }

}
