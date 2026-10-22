package com.example1.bddllm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.analysis.LlmFailure;
import com.example1.bddllm.analysis.LlmGateway;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.config.SchemaMigration;
import com.example1.bddllm.evaluation.EvaluationService;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.evidence.EvidenceRegistry;
import com.example1.bddllm.job.JobService;
import com.example1.bddllm.review.ReviewService;
import com.example1.bddllm.worker.AnalysisWorker;
import com.example1.bddllm.worker.JobQueue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bdd.test-migrate=true", "bdd.role=api", "bdd.test-lock-marker=true"})
@Import(DatabaseFixture.class)
class PostgresIntegrationTests {
    static final AtomicReference<String> DOCUMENT_PROMPT = new AtomicReference<>("bdd-document-analysis-v1.txt");
    static final AtomicReference<String> TEST_PROMPT = new AtomicReference<>("bdd-test-analysis-v1.txt");
    static final Path PROMPT_DIRECTORY = createPrompts();

    static Path createPrompts() {
        try {
            Path directory = Files.createTempDirectory("bdd-prompts-");
            for (String kind : List.of("document", "test")) {
                String name = "bdd-" + kind + "-analysis-v1.txt";
                String content = Files.readString(Path.of("src/main/resources/prompts/" + name));
                Files.writeString(directory.resolve(name), content);
                Files.writeString(directory.resolve(name.replace("-v1", "-v2")), content + "\n새 프롬프트 버전\n");
            }
            return directory;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        POSTGRES.start();
        properties.add("bdd.locks", () -> Path.of("build/isolated-test-locks").toAbsolutePath().toString());
        properties.add("bdd.prompts.root", () -> PROMPT_DIRECTORY.toUri().toString());
        properties.add("bdd.prompts.document-file", DOCUMENT_PROMPT::get);
        properties.add("bdd.prompts.test-file", TEST_PROMPT::get);
        properties.add("bdd.evidence-config", () -> "src/test/resources/legacy-evidence.toml");
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired DatabaseFixture db;
    @Autowired JobService jobs;
    @Autowired EvidenceRegistry evidence;
    @Autowired AnalysisWorker worker;
    @Autowired JobQueue queue;
    @Autowired ReviewService reviews;
    @Autowired EvaluationService evaluation;
    @MockitoBean LlmGateway llm;
    @MockitoBean com.example1.bddllm.evidence.GitHubSourceClient github;
    @LocalServerPort int port;
    ObjectNode input, result;

    @BeforeEach
    void resetDatabase() throws Exception {
        db.update(
                "TRUNCATE"
                    + " slack_notifications,candidate_reviews,analysis_jobs,error_events,error_groups"
                    + " RESTART IDENTITY CASCADE");
        db.update("TRUNCATE source_snapshots");
        DOCUMENT_PROMPT.set("bdd-document-analysis-v1.txt");
        TEST_PROMPT.set("bdd-test-analysis-v1.txt");
        input =
                (ObjectNode)
                        Json.parse(Files.readString(Path.of("samples/coupon_expired/event.json")));
        result =
                (ObjectNode)
                        Json.parse(
                                Files.readString(
                                        Path.of(
                                                "samples/evaluation/coupon_missing/reference.json")));
        when(llm.generate(anyString(), anyString(), anyString(), anyString())).thenReturn(result);
    }

    ObjectNode enqueue() {
        ErrorEvent errorEvent = ErrorEvent.read(input);
        return jobs.enqueue(errorEvent, evidence.forEvent(errorEvent));
    }

    long success() {
        long id = enqueue().path("job_id").asLong();
        assertThat(worker.once(id).path("status").asText()).isEqualTo("succeeded");
        return id;
    }

    long count(String table) {
        return db.one("SELECT count(*) n FROM " + table).path("n").asLong();
    }

    HttpResponse<String> http(String method, String path, String body) throws Exception {
        HttpRequest.Builder requestBuilder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (method.equals("POST"))
            requestBuilder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        else requestBuilder.GET();
        return HttpClient.newHttpClient().send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void repeatedEventReusesStoredJob() {
        ObjectNode first = enqueue();
        ObjectNode second = enqueue();
        assertThat(second.path("job_id")).isEqualTo(first.path("job_id"));
        assertThat(second.path("job_created").asBoolean()).isFalse();
        assertThat(second.path("event_created").asBoolean()).isFalse();
        assertThat(count("error_events")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(1);
    }

    @Test
    void newEventSameGroupDoesNotReanalyse() {
        long id = success();
        input.put("event_id", "second-event");
        ObjectNode second = enqueue();
        assertThat(second.path("job_id").asLong()).isEqualTo(id);
        assertThat(second.path("job_status").asText()).isEqualTo("succeeded");
        assertThat(second.path("occurrence_count").asInt()).isEqualTo(2);
        assertThat(worker.once(id).path("status").asText()).isEqualTo("idle");
        verify(llm, times(1)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sameEventDifferentPayloadRollsBack() {
        enqueue();
        input.put("deploy_sha", "changed");
        assertThatThrownBy(this::enqueue)
                .isInstanceOf(Problem.class)
                .extracting("code")
                .isEqualTo("event_conflict");
        assertThat(count("analysis_jobs")).isEqualTo(1);
        assertThat(count("error_events")).isEqualTo(1);
    }

    @Test
    void businessConditionCreatesSeparateJobs() {
        input.put("service", "noteu");
        ((ObjectNode) input.get("request"))
                .put("method", "GET")
                .put("route_template", ErrorEvent.CHAT_ROUTE);
        input.set("business_context", Json.obj("auth_state", "anonymous"));
        ObjectNode first = enqueue();
        input.put("event_id", "different-condition");
        input.set("business_context", Json.obj("auth_state", "authenticated"));
        ObjectNode second = enqueue();
        assertThat(second.path("group_id")).isEqualTo(first.path("group_id"));
        assertThat(second.path("job_id")).isNotEqualTo(first.path("job_id"));
    }

    @Test
    void changedBasisCreatesNewSnapshot() {
        ErrorEvent errorEvent = ErrorEvent.read(input);
        ObjectNode docs = evidence.forEvent(errorEvent);
        ObjectNode first = jobs.enqueue(errorEvent, docs);
        String original = docs.path("specification").path("content").asText();
        ObjectNode spec = (ObjectNode) docs.get("specification");
        spec.put("content", original + "\n변경된 명세\n")
                .put("sha256", Json.sha(original + "\n변경된 명세\n"));
        input.put("event_id", "new-document-event");
        ObjectNode second = jobs.enqueue(ErrorEvent.read(input), docs);
        assertThat(second.path("job_id")).isNotEqualTo(first.path("job_id"));
        assertThat(
                        db.one(
                                        "SELECT specification->>'content' content FROM"
                                                + " analysis_jobs WHERE id=?",
                                        first.path("job_id").asLong())
                                .path("content")
                                .asText())
                .isEqualTo(original);
    }

    @Test
    void concurrentIngestionMakesOneJob() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<ObjectNode>> futures = new ArrayList<Future<ObjectNode>>();
            for (int requestIndex = 0; requestIndex < 16; requestIndex++) futures.add(executor.submit(this::enqueue));
            long id = futures.getFirst().get().path("job_id").asLong();
            for (Future<ObjectNode> registrationFuture : futures)
                assertThat(registrationFuture.get().path("job_id").asLong()).isEqualTo(id);
        }
        assertThat(count("analysis_jobs")).isEqualTo(1);
        assertThat(count("error_events")).isEqualTo(1);
    }

    @Test
    void hundredDistinctHttpEventsReuseOneAnalysisAfterSuccess() throws Exception {
        List<HttpResponse<String>> responses = postEventsConcurrently(eventCopies("occurrence-", 100));
        assertThat(responses).hasSize(100);
        assertThat(responses).filteredOn(response -> response.statusCode() == 201).hasSize(1);
        assertThat(responses).filteredOn(response -> response.statusCode() == 200).hasSize(99);
        Set<Long> jobIds = new HashSet<>();
        Set<Long> eventRowIds = new HashSet<>();
        for (HttpResponse<String> response : responses) {
            JsonNode registration = Json.parse(response.body());
            assertThat(registration.path("event_created").asBoolean()).isTrue();
            jobIds.add(registration.path("job_id").asLong());
            eventRowIds.add(registration.path("event_row_id").asLong());
        }
        assertThat(jobIds).hasSize(1);
        assertThat(eventRowIds).hasSize(100);
        assertThat(count("error_events")).isEqualTo(100);
        assertThat(count("error_groups")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(1);
        assertThat(db.one("SELECT occurrence_count FROM error_groups").path("occurrence_count").asLong())
                .isEqualTo(100);
        verifyNoInteractions(llm, github);

        long jobId = jobIds.iterator().next();
        ObjectNode registeredSnapshot = analysisSnapshot(jobId);
        assertThat(eventRowIds).contains(registeredSnapshot.path("representative_event_id").asLong());
        assertThat(worker.once(jobId).path("status").asText()).isEqualTo("succeeded");
        ObjectNode completedResult = jobs.get(jobId);

        ObjectNode laterEvent = input.deepCopy().put("event_id", "occurrence-after-success");
        HttpResponse<String> reused = http("POST", "/events", Json.encode(laterEvent));
        assertThat(reused.statusCode()).isEqualTo(200);
        assertThat(Json.parse(reused.body()).path("job_id").asLong()).isEqualTo(jobId);
        assertThat(Json.parse(reused.body()).path("job_status").asText()).isEqualTo("succeeded");
        assertThat(count("error_events")).isEqualTo(101);
        assertThat(count("error_groups")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(1);
        assertThat(db.one("SELECT occurrence_count FROM error_groups").path("occurrence_count").asLong())
                .isEqualTo(101);
        assertThat(analysisSnapshot(jobId)).isEqualTo(registeredSnapshot);
        assertThat(jobs.get(jobId).path("result")).isEqualTo(completedResult.path("result"));
        assertThat(worker.once(jobId).path("status").asText()).isEqualTo("idle");
        verify(llm, times(1)).generate(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(github);
    }

    @Test
    void hundredHttpRetransmissionsDoNotIncreaseOccurrenceCount() throws Exception {
        HttpResponse<String> first = http("POST", "/events", Json.encode(input));
        assertThat(first.statusCode()).isEqualTo(201);
        JsonNode firstRegistration = Json.parse(first.body());
        long jobId = firstRegistration.path("job_id").asLong();
        ObjectNode originalSnapshot = analysisSnapshot(jobId);
        List<ObjectNode> retransmissions = new ArrayList<>();
        for (int requestIndex = 0; requestIndex < 100; requestIndex++) retransmissions.add(input.deepCopy());

        List<HttpResponse<String>> responses = postEventsConcurrently(retransmissions);
        assertThat(responses).hasSize(100);
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode registration = Json.parse(response.body());
            assertThat(registration.path("job_id").asLong()).isEqualTo(jobId);
            assertThat(registration.path("event_row_id")).isEqualTo(firstRegistration.path("event_row_id"));
            assertThat(registration.path("event_created").asBoolean()).isFalse();
            assertThat(registration.path("job_created").asBoolean()).isFalse();
            assertThat(registration.path("occurrence_count").asLong()).isEqualTo(1);
        }
        assertThat(count("error_events")).isEqualTo(1);
        assertThat(count("error_groups")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(1);
        assertThat(db.one("SELECT occurrence_count FROM error_groups").path("occurrence_count").asLong())
                .isEqualTo(1);
        assertThat(analysisSnapshot(jobId)).isEqualTo(originalSnapshot);
        verifyNoInteractions(llm, github);
    }

    @Test
    void concurrentHttpBusinessConditionsCreateOneJobPerCondition() throws Exception {
        input.put("service", "noteu");
        ((ObjectNode) input.get("request")).put("method", "GET")
                .put("path", "/subjects/1/chats/rooms").put("route_template", ErrorEvent.CHAT_ROUTE);
        List<ObjectNode> events = eventCopies("condition-", 100);
        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            events.get(eventIndex).set("business_context",
                    Json.obj("auth_state", eventIndex % 2 == 0 ? "anonymous" : "authenticated"));
        }

        List<HttpResponse<String>> responses = postEventsConcurrently(events);
        assertThat(responses).hasSize(100);
        assertThat(responses).filteredOn(response -> response.statusCode() == 201).hasSize(2);
        assertThat(responses).filteredOn(response -> response.statusCode() == 200).hasSize(98);
        Map<String, Set<Long>> jobsByCondition = new HashMap<>();
        for (int responseIndex = 0; responseIndex < responses.size(); responseIndex++) {
            JsonNode registration = Json.parse(responses.get(responseIndex).body());
            String authState = events.get(responseIndex).path("business_context").path("auth_state").asText();
            jobsByCondition.computeIfAbsent(authState, ignoredCondition -> new HashSet<>())
                    .add(registration.path("job_id").asLong());
            assertThat(registration.path("event_created").asBoolean()).isTrue();
        }
        assertThat(jobsByCondition.keySet()).containsExactlyInAnyOrder("anonymous", "authenticated");
        assertThat(jobsByCondition.get("anonymous")).hasSize(1);
        assertThat(jobsByCondition.get("authenticated")).hasSize(1);
        assertThat(jobsByCondition.get("anonymous")).doesNotContainAnyElementsOf(jobsByCondition.get("authenticated"));
        assertThat(count("error_events")).isEqualTo(100);
        assertThat(count("error_groups")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(2);
        assertThat(db.one("SELECT occurrence_count FROM error_groups").path("occurrence_count").asLong())
                .isEqualTo(100);
        List<ObjectNode> storedConditions = db.rows(
                "SELECT condition_hash, business_context, basis_hash FROM analysis_jobs");
        assertThat(storedConditions).extracting(stored -> stored.path("condition_hash").asText()).doesNotHaveDuplicates();
        assertThat(storedConditions).extracting(stored -> stored.path("basis_hash").asText()).containsOnly(
                storedConditions.getFirst().path("basis_hash").asText());
        for (ObjectNode storedCondition : storedConditions) {
            assertThat(storedCondition.path("business_context").path("subject_state").asText()).isEqualTo("unknown");
            assertThat(storedCondition.path("business_context").path("enrollment_state").asText()).isEqualTo("unknown");
        }
        verifyNoInteractions(llm, github);
    }

    @Test
    void changedSpecificationPreservesOriginalAnalysisSnapshot() {
        ErrorEvent originalEvent = ErrorEvent.read(input);
        ObjectNode originalDocs = evidence.forEvent(originalEvent);
        long originalJobId = jobs.enqueue(originalEvent, originalDocs).path("job_id").asLong();
        ObjectNode originalSnapshot = analysisSnapshot(originalJobId);
        ObjectNode changedDocs = originalDocs.deepCopy();
        ObjectNode changedSpecification = (ObjectNode) changedDocs.get("specification");
        String changedContent = changedSpecification.path("content").asText() + "\n새 정책을 확인한다.\n";
        changedSpecification.put("content", changedContent).put("sha256", Json.sha(changedContent));
        ErrorEvent changedEvent = ErrorEvent.read(input.deepCopy().put("event_id", "changed-specification"));

        long changedJobId = jobs.enqueue(changedEvent, changedDocs).path("job_id").asLong();
        assertThat(changedJobId).isNotEqualTo(originalJobId);
        assertThat(jobs.enqueue(changedEvent, changedDocs).path("job_id").asLong()).isEqualTo(changedJobId);
        assertThat(jobs.enqueue(originalEvent, originalDocs).path("job_id").asLong()).isEqualTo(originalJobId);
        assertThat(analysisSnapshot(originalJobId)).isEqualTo(originalSnapshot);
        ObjectNode changedSnapshot = analysisSnapshot(changedJobId);
        assertThat(changedSnapshot.path("basis_hash")).isNotEqualTo(originalSnapshot.path("basis_hash"));
        assertThat(changedSnapshot.path("specification").path("content").asText()).isEqualTo(changedContent);
        assertThat(changedSnapshot.path("bdd")).isEqualTo(originalSnapshot.path("bdd"));
        assertThat(changedSnapshot.path("prompt_file")).isEqualTo(originalSnapshot.path("prompt_file"));
        assertThat(count("error_events")).isEqualTo(2);
        assertThat(count("error_groups")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(2);
        verifyNoInteractions(llm, github);
    }

    @Test
    void changedPromptVersionPreservesOriginalAnalysisSnapshot() {
        ErrorEvent originalEvent = ErrorEvent.read(input);
        ObjectNode docs = evidence.forEvent(originalEvent);
        long originalJobId = jobs.enqueue(originalEvent, docs).path("job_id").asLong();
        ObjectNode originalSnapshot = analysisSnapshot(originalJobId);
        DOCUMENT_PROMPT.set("bdd-document-analysis-v2.txt");
        ErrorEvent changedEvent = ErrorEvent.read(input.deepCopy().put("event_id", "changed-prompt"));

        long changedJobId = jobs.enqueue(changedEvent, docs).path("job_id").asLong();
        assertThat(changedJobId).isNotEqualTo(originalJobId);
        assertThat(jobs.enqueue(changedEvent, docs).path("job_id").asLong()).isEqualTo(changedJobId);
        DOCUMENT_PROMPT.set("bdd-document-analysis-v1.txt");
        assertThat(jobs.enqueue(originalEvent, docs).path("job_id").asLong()).isEqualTo(originalJobId);
        assertThat(analysisSnapshot(originalJobId)).isEqualTo(originalSnapshot);
        ObjectNode changedSnapshot = analysisSnapshot(changedJobId);
        assertThat(changedSnapshot.path("basis_hash")).isNotEqualTo(originalSnapshot.path("basis_hash"));
        assertThat(changedSnapshot.path("prompt_file").asText()).isEqualTo("bdd-document-analysis-v2.txt");
        assertThat(changedSnapshot.path("basis").path("prompt_sha256"))
                .isNotEqualTo(originalSnapshot.path("basis").path("prompt_sha256"));
        assertThat(changedSnapshot.path("specification")).isEqualTo(originalSnapshot.path("specification"));
        assertThat(changedSnapshot.path("bdd")).isEqualTo(originalSnapshot.path("bdd"));
        assertThat(count("error_events")).isEqualTo(2);
        assertThat(count("error_groups")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(2);
        verifyNoInteractions(llm, github);
    }

    @Test
    void changedSourceCommitPreservesOriginalTestEvidence() {
        ObjectNode docs = sourceDocs();
        ErrorEvent originalEvent = ErrorEvent.read(input);
        long originalJobId = jobs.enqueue(originalEvent, docs).path("job_id").asLong();
        ObjectNode originalSnapshot = analysisSnapshot(originalJobId);
        String changedCommit = "b".repeat(40);
        when(github.fetch(any(), eq(changedCommit))).thenReturn(
                TestSourceTests.documents(TestSourceTests.CODE.replace("return 500;", "return 501;")));
        ErrorEvent changedEvent = ErrorEvent.read(input.deepCopy()
                .put("event_id", "changed-test-source").put("deploy_sha", changedCommit));

        long changedJobId = jobs.enqueue(changedEvent, docs).path("job_id").asLong();
        assertThat(changedJobId).isNotEqualTo(originalJobId);
        assertThat(jobs.enqueue(changedEvent, docs).path("job_id").asLong()).isEqualTo(changedJobId);
        assertThat(jobs.enqueue(originalEvent, docs).path("job_id").asLong()).isEqualTo(originalJobId);
        assertThat(analysisSnapshot(originalJobId)).isEqualTo(originalSnapshot);
        ObjectNode changedSnapshot = analysisSnapshot(changedJobId);
        assertThat(changedSnapshot.path("basis_hash")).isNotEqualTo(originalSnapshot.path("basis_hash"));
        assertThat(originalSnapshot.path("test_evidence").path("source_files").toString()).contains("return 500;");
        assertThat(changedSnapshot.path("test_evidence").path("source_files").toString()).contains("return 501;");
        assertThat(changedSnapshot.path("test_evidence").path("chunks"))
                .isNotEqualTo(originalSnapshot.path("test_evidence").path("chunks"));
        assertThat(changedSnapshot.path("specification")).isEqualTo(originalSnapshot.path("specification"));
        assertThat(changedSnapshot.path("bdd")).isEqualTo(originalSnapshot.path("bdd"));
        assertThat(changedSnapshot.path("prompt_file")).isEqualTo(originalSnapshot.path("prompt_file"));
        assertThat(count("source_snapshots")).isEqualTo(2);
        assertThat(count("error_events")).isEqualTo(2);
        assertThat(count("error_groups")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(2);
        verify(github, times(1)).fetch(any(), eq(TestSourceTests.SHA));
        verify(github, times(1)).fetch(any(), eq(changedCommit));
        verifyNoInteractions(llm);
    }

    @Test
    void conflictingHttpPayloadDoesNotChangeStoredState() throws Exception {
        HttpResponse<String> first = http("POST", "/events", Json.encode(input));
        assertThat(first.statusCode()).isEqualTo(201);
        long jobId = Json.parse(first.body()).path("job_id").asLong();
        ObjectNode originalSnapshot = analysisSnapshot(jobId);
        ObjectNode originalEvent = db.one("SELECT payload, payload_sha256, group_id FROM error_events");
        ObjectNode originalGroup = db.one("SELECT fingerprint, signature, occurrence_count FROM error_groups");
        ObjectNode conflictingEvent = input.deepCopy();
        ((ObjectNode) conflictingEvent.get("error")).put("type", "DifferentException");

        HttpResponse<String> conflict = http("POST", "/events", Json.encode(conflictingEvent));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(Json.parse(conflict.body()).path("detail").path("code").asText()).isEqualTo("event_conflict");
        assertThat(count("error_events")).isEqualTo(1);
        assertThat(count("error_groups")).isEqualTo(1);
        assertThat(count("analysis_jobs")).isEqualTo(1);
        assertThat(analysisSnapshot(jobId)).isEqualTo(originalSnapshot);
        assertThat(db.one("SELECT payload, payload_sha256, group_id FROM error_events")).isEqualTo(originalEvent);
        assertThat(db.one("SELECT fingerprint, signature, occurrence_count FROM error_groups")).isEqualTo(originalGroup);
        verifyNoInteractions(llm, github);
    }

    @Test
    void eightConcurrentWorkersCallLlmOnce() throws Exception {
        HttpResponse<String> registration = http("POST", "/events", Json.encode(input));
        assertThat(registration.statusCode()).isEqualTo(201);
        long jobId = Json.parse(registration.body()).path("job_id").asLong();
        CountDownLatch workersReady = new CountDownLatch(8);
        CountDownLatch startWorkers = new CountDownLatch(1);
        CountDownLatch analysisEntered = new CountDownLatch(1);
        CountDownLatch releaseAnalysis = new CountDownLatch(1);
        CountDownLatch idleWorkers = new CountDownLatch(7);
        when(llm.generate(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            analysisEntered.countDown();
            assertThat(releaseAnalysis.await(30, TimeUnit.SECONDS)).as("release the single analysis").isTrue();
            return result.deepCopy();
        });
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<ObjectNode>> workerResults = new ArrayList<>();
        try {
            for (int workerIndex = 0; workerIndex < 8; workerIndex++) {
                workerResults.add(executor.submit(() -> {
                    workersReady.countDown();
                    assertThat(startWorkers.await(10, TimeUnit.SECONDS)).as("start workers together").isTrue();
                    ObjectNode outcome = worker.once(jobId);
                    if (outcome.path("status").asText().equals("idle")) idleWorkers.countDown();
                    return outcome;
                }));
            }
            assertThat(workersReady.await(10, TimeUnit.SECONDS)).isTrue();
            startWorkers.countDown();
            assertThat(analysisEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(idleWorkers.await(10, TimeUnit.SECONDS)).as("seven workers skip the running job").isTrue();
            assertThat(jobs.get(jobId).path("status").asText()).isEqualTo("running");
            assertThat(jobs.get(jobId).path("attempt_count").asInt()).isEqualTo(1);
            assertThat(db.one("SELECT result FROM analysis_jobs WHERE id=?", jobId).path("result").isNull()).isTrue();
            verify(llm, times(1)).generate(anyString(), anyString(), anyString(), anyString());
            releaseAnalysis.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<ObjectNode> workerResult : workerResults) {
                statuses.add(workerResult.get(30, TimeUnit.SECONDS).path("status").asText());
            }
            assertThat(statuses).filteredOn("succeeded"::equals).hasSize(1);
            assertThat(statuses).filteredOn("idle"::equals).hasSize(7);
            assertThat(count("analysis_jobs")).isEqualTo(1);
            assertThat(jobs.get(jobId).path("result")).isEqualTo(result);
            assertThat(jobs.get(jobId).path("attempt_count").asInt()).isEqualTo(1);
            assertThat(worker.once(jobId).path("status").asText()).isEqualTo("idle");
            verify(llm, times(1)).generate(anyString(), anyString(), anyString(), anyString());
            verifyNoInteractions(github);
        } finally {
            startWorkers.countDown();
            releaseAnalysis.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<ObjectNode> eventCopies(String eventIdPrefix, int eventCount) {
        List<ObjectNode> events = new ArrayList<>();
        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            events.add(input.deepCopy().put("event_id", eventIdPrefix + eventIndex));
        }
        return events;
    }

    private List<HttpResponse<String>> postEventsConcurrently(List<ObjectNode> events) throws Exception {
        int concurrentRequests = Math.min(10, events.size());
        CountDownLatch requestsReady = new CountDownLatch(concurrentRequests);
        CountDownLatch startRequests = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        List<Future<HttpResponse<String>>> pendingResponses = new ArrayList<>();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            try {
                for (ObjectNode event : events) {
                    String payload = Json.encode(event);
                    pendingResponses.add(executor.submit(() -> {
                        requestsReady.countDown();
                        assertThat(startRequests.await(10, TimeUnit.SECONDS)).as("start HTTP requests together").isTrue();
                        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events"))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                        return client.send(request, HttpResponse.BodyHandlers.ofString());
                    }));
                }
                assertThat(requestsReady.await(10, TimeUnit.SECONDS)).isTrue();
                startRequests.countDown();
                List<HttpResponse<String>> responses = new ArrayList<>();
                for (Future<HttpResponse<String>> pendingResponse : pendingResponses) {
                    responses.add(pendingResponse.get(60, TimeUnit.SECONDS));
                }
                return responses;
            } finally {
                startRequests.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private ObjectNode analysisSnapshot(long jobId) {
        return db.one("SELECT representative_event_id, basis_hash, basis, specification, bdd, prompt_file,"
                + " result_schema, condition_hash, business_context, test_evidence FROM analysis_jobs WHERE id=?", jobId);
    }

    @Test
    void runningWorkerIsNotClaimedTwice() throws Exception {
        long id = enqueue().path("job_id").asLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(llm.generate(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            entered.countDown();
                            release.await(10, TimeUnit.SECONDS);
                            return result;
                        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ObjectNode> analysisFuture = executor.submit(() -> worker.once(id));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.once(id).path("status").asText()).isEqualTo("idle");
            db.update(
                    "UPDATE analysis_jobs SET started_at=now()-interval '10 minutes' WHERE id=?",
                    id);
            assertThatThrownBy(() -> queue.requeue(id, true)).isInstanceOf(Problem.class);
            release.countDown();
            assertThat(analysisFuture.get().path("status").asText()).isEqualTo("succeeded");
        }
    }

    @Test
    void noDatabaseTransactionDuringLlm() {
        when(llm.generate(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            assertThat(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return result;
                        });
        success();
    }

    @Test
    void transientErrorsRespectAttemptBudget() {
        long id = enqueue().path("job_id").asLong();
        when(llm.generate(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new LlmFailure("llm_server_error"));
        for (int attemptNumber = 1; attemptNumber <= 3; attemptNumber++) {
            ObjectNode out = worker.once(id);
            assertThat(out.path("status").asText()).isEqualTo(attemptNumber < 3 ? "retry_scheduled" : "failed");
            db.update(
                    "UPDATE analysis_jobs SET next_attempt_at=now()-interval '1 second' WHERE id=?",
                    id);
        }
        assertThat(jobs.get(id).path("attempt_count").asInt()).isEqualTo(3);
        assertThatThrownBy(() -> queue.requeue(id, false)).isInstanceOf(Problem.class);
    }

    @Test
    void uncertainTimeoutDoesNotAutoRetry() {
        long id = enqueue().path("job_id").asLong();
        when(llm.generate(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new LlmFailure("llm_timeout"));
        assertThat(worker.once(id).path("status").asText()).isEqualTo("failed");
        assertThat(jobs.get(id).path("next_attempt_at").isNull()).isTrue();
        queue.requeue(id, false);
        assertThat(jobs.get(id).path("attempt_count").asInt()).isEqualTo(1);
    }

    @Test
    void staleUnlockedJobRecoversWithoutResettingBudget() {
        long id = enqueue().path("job_id").asLong();
        try (JobQueue.Claim claim = queue.claim("ollama", id)) {
            db.update(
                    "UPDATE analysis_jobs SET started_at=now()-interval '6 minutes' WHERE id=?",
                    id);
        }
        queue.requeue(id, true);
        assertThat(jobs.get(id).path("status").asText()).isEqualTo("pending");
        assertThat(jobs.get(id).path("attempt_count").asInt()).isEqualTo(1);
    }

    @Test
    void recentJobCannotRecover() {
        long id = enqueue().path("job_id").asLong();
        try (JobQueue.Claim claim = queue.claim("ollama", id)) {}
        assertThatThrownBy(() -> queue.requeue(id, true)).isInstanceOf(Problem.class);
    }

    @Test
    void stolenClaimCannotWrite() {
        long id = enqueue().path("job_id").asLong();
        try (JobQueue.Claim claim = queue.claim("ollama", id)) {
            db.update("UPDATE analysis_jobs SET claim_token=? WHERE id=?", UUID.randomUUID(), id);
            assertThatThrownBy(() -> queue.complete(claim, result, "feature"))
                    .isInstanceOf(Problem.class);
        }
    }

    @Test
    void corruptedSnapshotNeverCallsLlm() {
        long id = enqueue().path("job_id").asLong();
        db.update("UPDATE analysis_jobs SET prompt_file='bdd-document-analysis-v2.txt' WHERE id=?", id);
        assertThat(worker.once(id).path("error_code").asText()).isEqualTo("invalid_snapshot");
        verify(llm, never()).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void newJobsStoreOnlyVersionedPromptReference() {
        long id = enqueue().path("job_id").asLong();
        ObjectNode stored = db.one("SELECT prompt, prompt_file, basis FROM analysis_jobs WHERE id=?", id);
        assertThat(stored.path("prompt").isNull()).isTrue();
        assertThat(stored.path("prompt_file").asText()).isEqualTo("bdd-document-analysis-v1.txt");
        assertThat(stored.path("basis").path("prompt_file")).isEqualTo(stored.path("prompt_file"));
        assertThat(jobs.get(id).path("prompt_file")).isEqualTo(stored.path("prompt_file"));
        assertThat(enqueue().path("job_id").asLong()).isEqualTo(id);
        assertThat(count("analysis_jobs")).isEqualTo(1);
    }

    @Test
    void pendingJobKeepsRegisteredVersionWhenDefaultChanges() throws Exception {
        long original = enqueue().path("job_id").asLong();
        DOCUMENT_PROMPT.set("bdd-document-analysis-v2.txt");
        long changed = enqueue().path("job_id").asLong();
        assertThat(changed).isNotEqualTo(original);
        assertThat(enqueue().path("job_id").asLong()).isEqualTo(changed);
        assertThat(worker.once(original).path("status").asText()).isEqualTo("succeeded");
        verify(llm).generate(anyString(), anyString(), eq(Files.readString(PROMPT_DIRECTORY.resolve("bdd-document-analysis-v1.txt"))), anyString());
        assertThat(worker.once(changed).path("status").asText()).isEqualTo("succeeded");
        verify(llm).generate(anyString(), anyString(), eq(Files.readString(PROMPT_DIRECTORY.resolve("bdd-document-analysis-v2.txt"))), anyString());
    }

    @Test
    void missingOrModifiedVersionStopsBeforeLlm() {
        long missing = enqueue().path("job_id").asLong();
        ObjectNode basis = db.one("SELECT basis FROM analysis_jobs WHERE id=?", missing).withObject("basis");
        basis.put("prompt_file", "bdd-document-analysis-v99.txt");
        db.update("UPDATE analysis_jobs SET prompt_file=?, basis=cast(? as jsonb), basis_hash=? WHERE id=?",
                "bdd-document-analysis-v99.txt", Json.encode(basis), AnalysisPolicy.basisHash(basis), missing);
        assertThat(worker.once(missing).path("error_code").asText()).isEqualTo("prompt_file_missing");
        DOCUMENT_PROMPT.set("bdd-document-analysis-v2.txt");
        long modified = enqueue().path("job_id").asLong();
        db.update("UPDATE analysis_jobs SET basis=jsonb_set(basis, '{prompt_sha256}', '\"wrong\"') WHERE id=?", modified);
        assertThat(worker.once(modified).path("error_code").asText()).isEqualTo("prompt_hash_mismatch");
        verify(llm, never()).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void legacyPromptSnapshotStillExecutesUnchanged() throws Exception {
        long id = enqueue().path("job_id").asLong();
        String legacyPrompt = Files.readString(PROMPT_DIRECTORY.resolve("bdd-document-analysis-v1.txt"));
        ObjectNode basis = db.one("SELECT basis FROM analysis_jobs WHERE id=?", id).withObject("basis");
        basis.remove("prompt_file");
        db.update("UPDATE analysis_jobs SET prompt=?, prompt_file=NULL, basis=cast(? as jsonb), basis_hash=? WHERE id=?",
                legacyPrompt, Json.encode(basis), AnalysisPolicy.basisHash(basis), id);
        DOCUMENT_PROMPT.set("bdd-document-analysis-v2.txt");
        assertThat(worker.once(id).path("status").asText()).isEqualTo("succeeded");
        verify(llm).generate(anyString(), anyString(), eq(legacyPrompt), anyString());
        assertThat(jobs.get(id).has("prompt_file")).isFalse();
        assertThat(db.one("SELECT prompt FROM analysis_jobs WHERE id=?", id).path("prompt").asText()).isEqualTo(legacyPrompt);
    }

    @Test
    void invalidQuoteDoesNotSucceed() {
        long id = enqueue().path("job_id").asLong();
        ObjectNode invalid = result.deepCopy();
        ((ObjectNode) invalid.path("candidates").get(0).path("then").get(0))
                .put("specification_quote", "not in specification");
        when(llm.generate(anyString(), anyString(), anyString(), anyString())).thenReturn(invalid);
        assertThat(worker.once(id).path("error_code").asText()).isEqualTo("invalid_result");
    }

    @Test
    void reviewEditRejectApproveAndExport() {
        long id = success();
        JsonNode original = reviews.detail(id).path("candidates").get(0).path("original");
        JsonNode candidate = original.deepCopy();
        ((ObjectNode) candidate).put("title", "검토자가 수정한 시나리오");
        reviews.submit(
                id,
                1,
                Json.obj(
                        "action",
                        "edit",
                        "reviewer",
                        "tester",
                        "reason",
                        "제목 개선",
                        "expected_revision",
                        0,
                        "candidate",
                        candidate));
        assertThatThrownBy(() -> reviews.approved(id)).isInstanceOf(Problem.class);
        reviews.submit(
                id,
                1,
                Json.obj(
                        "action",
                        "reject",
                        "reviewer",
                        "tester",
                        "reason",
                        "다시 검토",
                        "expected_revision",
                        1));
        reviews.submit(
                id, 1, Json.obj("action", "approve", "reviewer", "tester", "expected_revision", 2));
        ObjectNode detail = reviews.detail(id);
        assertThat(detail.path("candidates").get(0).path("history")).hasSize(3);
        assertThat(detail.path("candidates").get(0).path("original")).isEqualTo(original);
        assertThat(reviews.approved(id)).contains("검토자가 수정한 시나리오");
        assertThat(count("analysis_jobs")).isEqualTo(1);
    }

    @Test
    void staleReviewAndDoubleApprovalConflict() {
        long id = success();
        ObjectNode cmd =
                Json.obj("action", "approve", "reviewer", "tester", "expected_revision", 0);
        reviews.submit(id, 1, cmd);
        assertThatThrownBy(() -> reviews.submit(id, 1, cmd)).isInstanceOf(Problem.class);
        cmd.put("expected_revision", 1);
        assertThatThrownBy(() -> reviews.submit(id, 1, cmd)).isInstanceOf(Problem.class);
        assertThat(count("candidate_reviews")).isEqualTo(1);
    }

    @Test
    void corruptReviewHistoryFailsClosed() {
        long id = success();
        reviews.submit(
                id, 1, Json.obj("action", "approve", "reviewer", "tester", "expected_revision", 0));
        db.update("UPDATE candidate_reviews SET revision=3 WHERE job_id=?", id);
        assertThatThrownBy(() -> reviews.detail(id))
                .isInstanceOf(Problem.class)
                .extracting("code")
                .isEqualTo("invalid_stored_review");
    }

    @Test
    void httpContractAndStaticReviewPage() throws Exception {
        assertThat(http("GET", "/openapi.json", null).statusCode()).isEqualTo(200);
        assertThat(http("GET", "/docs", null).statusCode()).isEqualTo(302);
        HttpResponse<String> first = http("POST", "/events", Json.encode(input));
        assertThat(first.statusCode()).isEqualTo(201);
        HttpResponse<String> second = http("POST", "/events", Json.encode(input));
        assertThat(second.statusCode()).isEqualTo(200);
        long id = Json.parse(first.body()).path("job_id").asLong();
        assertThat(http("GET", "/jobs/" + id, null).statusCode()).isEqualTo(200);
        assertThat(http("GET", "/jobs/999999", null).statusCode()).isEqualTo(404);
        assertThat(http("POST", "/events", "{\"token\":\"secret\"}").body())
                .doesNotContain("secret");
        HttpResponse<String> page = http("GET", "/reviews", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Security-Policy")).isPresent();
    }

    @Test
    void evaluationSuccessfulResponsesAreCachedAndPromptChangeInvalidates() throws Exception {
        Path output = Files.createTempDirectory("bdd-eval-");
        List<EvaluationService.Case> cases = evaluation.load("samples/evaluation");
        when(llm.generate(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            String evidence = inv.getArgument(3);
                            for (EvaluationService.Case evaluationCase : cases)
                                if (AnalysisPolicy.evidence(evaluationCase.event(), evaluationCase.spec(), evaluationCase.bdd())
                                        .equals(evidence)) return evaluationCase.reference();
                            throw new AssertionError();
                        });
        assertThat(
                        evaluation
                                .run("samples/evaluation", output.toString(), false)
                                .path("automated_pass")
                                .asBoolean())
                .isTrue();
        ObjectNode reused = evaluation.run("samples/evaluation", output.toString(), false);
        for (JsonNode caseReport : reused.path("cases"))
            assertThat(caseReport.path("cache_hit").asBoolean()).isTrue();
        verify(llm, times(4)).generate(anyString(), anyString(), anyString(), anyString());
        DOCUMENT_PROMPT.set("bdd-document-analysis-v2.txt");
        ObjectNode changed = evaluation.run("samples/evaluation", output.toString(), false);
        for (JsonNode caseReport : changed.path("cases"))
            assertThat(caseReport.path("cache_hit").asBoolean()).isFalse();
        verify(llm, times(8)).generate(anyString(), anyString(), anyString(), anyString());
    }

    ObjectNode sourceDocs() {
        input.put("service", "noteu").put("deploy_sha", TestSourceTests.SHA);
        ((ObjectNode) input.get("request")).put("method", "GET").put("path", "/subjects/1/chats/rooms")
                .put("route_template", ErrorEvent.CHAT_ROUTE);
        input.set("business_context", Json.obj("auth_state", "anonymous"));
        ErrorEvent event = ErrorEvent.read(input);
        ObjectNode docs = evidence.forEvent(event);
        docs.set("test_source_config", TestSourceTests.config().json());
        when(github.fetch(any(), anyString())).thenReturn(TestSourceTests.documents(TestSourceTests.CODE));
        return docs;
    }

    void sourceLlmResponse() {
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            JsonNode data = Json.parse(call.getArgument(3));
            ObjectNode snapshot = (ObjectNode) data.path("test_sources");
            ObjectNode response = TestSourceTests.assessmentResult(snapshot, "characterization_only");
            ObjectNode candidate = Json.obj("title", "비로그인 요청은 로그인 페이지로 이동한다", "gap_type", "missing_scenario",
                    "reason", "현재 500을 확인하는 테스트는 로그인 이동을 보장하지 않습니다.", "existing_bdd_quotes", Json.array(),
                    "given", List.of("사용자가 로그인하지 않았다"), "when", "채팅방 목록을 요청한다",
                    "then", List.of(Json.obj("behavior", "로그인 페이지로 리다이렉트한다", "specification_quote", "로그인 페이지로 리다이렉트")));
            ((com.fasterxml.jackson.databind.node.ArrayNode) response.get("candidates")).add(candidate);
            ((ObjectNode) response.path("test_assessments").get(0)).put("candidate_no", 1);
            return response;
        }).when(llm).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testEvidenceIsCachedAndWorkerUsesFrozenSnapshotWithoutGithub() throws Exception {
        ObjectNode docs = sourceDocs(); sourceLlmResponse();
        ErrorEvent event = ErrorEvent.read(input);
        long id = jobs.enqueue(event, docs).path("job_id").asLong();
        input.put("event_id", "same-group-another-occurrence");
        assertThat(jobs.enqueue(ErrorEvent.read(input), docs).path("job_id").asLong()).isEqualTo(id);
        assertThat(count("source_snapshots")).isEqualTo(1);
        verify(github, times(1)).fetch(any(), anyString());
        reset(github); // a queued worker must never touch the live repository
        assertThat(worker.once(id).path("status").asText()).isEqualTo("succeeded");
        assertThat(worker.once(id).path("status").asText()).isEqualTo("idle");
        verifyNoInteractions(github);
        verify(llm, times(1)).generate(anyString(), anyString(), contains("실제 테스트 근거"), contains("test_sources"));
        assertThat(jobs.get(id).path("coverage_scope").asText()).isEqualTo("bdd_and_selected_tests");
        assertThat(jobs.get(id).path("result").path("test_assessments").get(0).path("status").asText()).isEqualTo("characterization_only");
        assertThat(jobs.evidence(id).path("test_evidence").has("source_files")).isFalse();
        assertThat(http("GET", "/jobs/" + id + "/evidence", null).statusCode()).isEqualTo(200);
        assertThat(http("GET", "/jobs/999999/evidence", null).statusCode()).isEqualTo(404);
        assertThat(reviews.detail(id).path("test_assessments")).hasSize(1);
    }

    @Test
    void testEvidenceReviewKeepsEditRejectApproveHistoryAndApprovedExport() {
        ObjectNode docs = sourceDocs(); sourceLlmResponse();
        long id = jobs.enqueue(ErrorEvent.read(input), docs).path("job_id").asLong();
        assertThat(worker.once(id).path("status").asText()).isEqualTo("succeeded");
        ObjectNode candidate = reviews.detail(id).path("candidates").get(0).path("current").deepCopy();
        candidate.put("title", "비로그인 채팅방 목록 요청의 로그인 이동");
        reviews.submit(id, 1, Json.obj("action", "edit", "reviewer", "test-reviewer", "reason", "제목 구체화", "expected_revision", 0, "candidate", candidate));
        reviews.submit(id, 1, Json.obj("action", "reject", "reviewer", "test-reviewer", "reason", "재검토", "expected_revision", 1));
        reviews.submit(id, 1, Json.obj("action", "approve", "reviewer", "test-reviewer", "expected_revision", 2));
        assertThat(reviews.detail(id).path("candidates").get(0).path("history")).hasSize(3);
        assertThat(reviews.approved(id)).contains("비로그인 채팅방 목록 요청의 로그인 이동").doesNotContain("assertEquals(500");
        assertThatThrownBy(() -> reviews.submit(id, 1, Json.obj("action", "approve", "reviewer", "test", "expected_revision", 0)))
                .isInstanceOf(Problem.class).extracting("code").isEqualTo("review_conflict");
    }

    @Test
    void concurrentSourceRegistrationCreatesOneCachedSnapshotAndOneJob() throws Exception {
        ObjectNode docs = sourceDocs(); ErrorEvent event = ErrorEvent.read(input);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<ObjectNode>> futures = new ArrayList<>();
            for (int requestIndex = 0; requestIndex < 16; requestIndex++) futures.add(executor.submit(() -> jobs.enqueue(event, docs)));
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (Future<ObjectNode> future : futures) ids.add(future.get().path("job_id").asLong());
            assertThat(ids).hasSize(1);
        }
        assertThat(count("analysis_jobs")).isEqualTo(1);
        assertThat(count("source_snapshots")).isEqualTo(1);
        verify(github, times(1)).fetch(any(), anyString());
    }

    @Test
    void changedSelectionConfigurationOrConditionsCreatesNewAnalysis() {
        ObjectNode docs = sourceDocs();
        long first = jobs.enqueue(ErrorEvent.read(input), docs).path("job_id").asLong();
        ((ObjectNode) docs.get("test_source_config")).set("search_terms", Json.value(List.of("changed-policy")));
        long second = jobs.enqueue(ErrorEvent.read(input), docs).path("job_id").asLong();
        assertThat(second).isNotEqualTo(first);
        input.put("event_id", "authenticated-event");
        input.set("business_context", Json.obj("auth_state", "authenticated"));
        assertThat(jobs.enqueue(ErrorEvent.read(input), docs).path("job_id").asLong()).isNotEqualTo(second);
        verify(github, times(1)).fetch(any(), anyString());
    }

    @Test
    void sourceFailureCreatesNoEventOrAnalysisAndDoesNotCallLlm() {
        ObjectNode docs = sourceDocs();
        when(github.fetch(any(), anyString())).thenThrow(new Problem(422, "source_commit_not_found", "missing"));
        assertThatThrownBy(() -> jobs.enqueue(ErrorEvent.read(input), docs)).isInstanceOf(Problem.class)
                .extracting("code").isEqualTo("source_commit_not_found");
        assertThat(count("analysis_jobs")).isZero(); assertThat(count("error_events")).isZero();
        verifyNoInteractions(llm);
    }

    @Test
    void invalidTestCitationFailsWithoutRetryAndPreservesSnapshot() {
        ObjectNode docs = sourceDocs(); sourceLlmResponse();
        long id = jobs.enqueue(ErrorEvent.read(input), docs).path("job_id").asLong();
        doAnswer(call -> {
            ObjectNode response = TestSourceTests.assessmentResult((ObjectNode) Json.parse(call.getArgument(3)).path("test_sources"), "characterization_only");
            ((ObjectNode) response.path("test_assessments").get(0).path("citations").get(0)).put("quote", "invented redirect assertion");
            return response;
        }).when(llm).generate(anyString(), anyString(), anyString(), anyString());
        assertThat(worker.once(id).path("error_code").asText()).isEqualTo("invalid_result");
        assertThat(jobs.get(id).path("status").asText()).isEqualTo("failed");
        assertThat(jobs.evidence(id).path("test_evidence").path("chunks")).isNotEmpty();
        assertThat(worker.once(id).path("status").asText()).isEqualTo("idle");
        verify(llm, times(1)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void eventConflictStillReturns409BeforeGithubLookup() {
        ObjectNode docs = sourceDocs();
        jobs.enqueue(ErrorEvent.read(input), docs);
        reset(github);
        input.put("deploy_sha", "b".repeat(40));
        assertThatThrownBy(() -> jobs.enqueue(ErrorEvent.read(input), docs)).isInstanceOf(Problem.class)
                .extracting("status").isEqualTo(409);
        verifyNoInteractions(github);
        assertThat(count("analysis_jobs")).isEqualTo(1);
    }

    private Flyway legacyDatabase(String schema) {
        Flyway flyway =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .schemas(schema)
                        .defaultSchema(schema)
                        .baselineVersion("6")
                        .target("6")
                        .locations("classpath:db/migration")
                        .load();
        flyway.migrate();
        db.update("DROP TABLE " + schema + ".flyway_schema_history");
        return Flyway.configure().configuration(flyway.getConfiguration()).target("latest").load();
    }

    @Test
    void legacyBaselinePreservesDataAndDoesNotRunOldMigrations() {
        String schema = "baseline_valid";
        try {
            Flyway flyway = legacyDatabase(schema);
            db.update(
                    "INSERT INTO "
                            + schema
                            + ".error_groups(fingerprint,signature) VALUES"
                            + " ('preserved','{}'::jsonb)");
            new SchemaMigration().migrationStrategy(true, "migrate", false).migrate(flyway);
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
            assertThat(
                            db.one(
                                            "SELECT count(*) n FROM "
                                                    + schema
                                                    + ".error_groups WHERE fingerprint='preserved'")
                                    .path("n")
                                    .asInt())
                    .isEqualTo(1);
        } finally {
            db.update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void legacyBaselineRejectsWrongColumnTypes() {
        String schema = "baseline_wrong_type";
        try {
            Flyway flyway = legacyDatabase(schema);
            db.update(
                    "ALTER TABLE "
                            + schema
                            + ".error_groups ALTER COLUMN occurrence_count TYPE integer");
            assertThatThrownBy(
                            () ->
                                    new SchemaMigration()
                                            .migrationStrategy(true, "migrate", false)
                                            .migrate(flyway))
                    .isInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("baseline_column_mismatch");
            assertThat(flyway.info().current()).isNull();
        } finally {
            db.update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void legacyBaselineRejectsMissingUniqueConstraint() {
        String schema = "baseline_no_unique";
        try {
            Flyway flyway = legacyDatabase(schema);
            db.update(
                    "ALTER TABLE "
                            + schema
                            + ".error_groups DROP CONSTRAINT error_groups_fingerprint_key");
            assertThatThrownBy(
                            () ->
                                    new SchemaMigration()
                                            .migrationStrategy(true, "migrate", false)
                                            .migrate(flyway))
                    .isInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("baseline_unique_index_missing");
            assertThat(flyway.info().current()).isNull();
        } finally {
            db.update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
