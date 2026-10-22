package com.example1.bddllm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example1.bddllm.analysis.LlmFailure;
import com.example1.bddllm.analysis.LlmGateway;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.evidence.EvidenceRegistry;
import com.example1.bddllm.evidence.GitHubSourceClient;
import com.example1.bddllm.job.JobService;
import com.example1.bddllm.worker.AnalysisWorker;
import com.example1.bddllm.worker.ExecutionLocks;
import com.example1.bddllm.worker.JobQueue;
import com.example1.bddllm.worker.ScheduledRetryService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bdd.test-migrate=true", "bdd.role=api", "bdd.provider=ollama",
        "bdd.evidence-config=src/test/resources/legacy-evidence.toml"})
@Import(DatabaseFixture.class)
class ScheduledRetryIntegrationTests {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        POSTGRES.start();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("bdd.locks", () -> Path.of("build/scheduled-retry-test-locks").toAbsolutePath().toString());
    }

    @Autowired DatabaseFixture database;
    @Autowired JobService jobs;
    @Autowired EvidenceRegistry evidence;
    @Autowired AnalysisWorker worker;
    @Autowired JobQueue queue;
    @Autowired ScheduledRetryService retries;
    @Autowired ExecutionLocks executionLocks;
    @MockitoBean LlmGateway llm;
    @MockitoBean GitHubSourceClient github;
    @LocalServerPort int port;
    ObjectNode input;
    ObjectNode result;

    @BeforeEach
    void resetDatabase() throws Exception {
        database.update("TRUNCATE error_groups RESTART IDENTITY CASCADE");
        input = (ObjectNode) Json.parse(Files.readString(Path.of("samples/coupon_expired/event.json")));
        result = (ObjectNode) Json.parse(Files.readString(Path.of("samples/evaluation/coupon_missing/reference.json")));
        doReturn(result).when(llm).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void serverFailureGetsOneFourthAttemptWithFrozenEvidence() {
        assertFourthAttemptSucceeds("llm_server_error");
    }

    @Test
    void rateLimitGetsOneFourthAttemptWithFrozenEvidence() {
        assertFourthAttemptSucceeds("llm_rate_limited");
    }

    private void assertFourthAttemptSucceeds(String errorCode) {
        long jobId = exhaust(errorCode, null);
        ObjectNode snapshot = snapshot(jobId);
        ObjectNode failed = jobs.get(jobId);
        assertThat(failed.path("scheduled_retry_count").asInt()).isZero();
        assertThat(failed.path("scheduled_retry_at").isTextual()).isTrue();
        assertThatThrownBy(() -> queue.requeue(jobId, false)).isInstanceOf(Problem.class);

        assertThat(retries.requeueDue("ollama")).isEqualTo(1);
        ObjectNode pending = jobs.get(jobId);
        assertThat(pending.path("status").asText()).isEqualTo("pending");
        assertThat(pending.path("attempt_count").asInt()).isEqualTo(3);
        assertThat(pending.path("max_attempts").asInt()).isEqualTo(4);
        assertThat(pending.path("scheduled_retry_count").asInt()).isEqualTo(1);
        assertThat(pending.path("scheduled_retry_at").isNull()).isTrue();
        assertThat(snapshot(jobId)).isEqualTo(snapshot);
        verify(llm, times(3)).generate(anyString(), anyString(), anyString(), anyString());

        doReturn(result).when(llm).generate(anyString(), anyString(), anyString(), anyString());
        assertThat(worker.once(jobId).path("status").asText()).isEqualTo("succeeded");
        assertThat(jobs.get(jobId).path("result")).isEqualTo(result);
        assertThat(jobs.get(jobId).path("attempt_count").asInt()).isEqualTo(4);
        assertThat(snapshot(jobId)).isEqualTo(snapshot);
        assertThat(retries.requeueDue("ollama")).isZero();
        assertThat(worker.once(jobId).path("status").asText()).isEqualTo("idle");
        verify(llm, times(4)).generate(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(github);
    }

    @Test
    void fourthFailureIsFinalAndNeverGetsAnotherReservation() {
        long jobId = exhaust("llm_server_error", null);
        assertThat(retries.requeueDue("ollama")).isEqualTo(1);
        assertThat(worker.once(jobId).path("status").asText()).isEqualTo("failed");
        for (int scanIndex = 0; scanIndex < 3; scanIndex++) {
            assertThat(retries.requeueDue("ollama")).isZero();
            assertThat(worker.once(jobId).path("status").asText()).isEqualTo("idle");
        }
        ObjectNode failed = jobs.get(jobId);
        assertThat(failed.path("attempt_count").asInt()).isEqualTo(4);
        assertThat(failed.path("max_attempts").asInt()).isEqualTo(4);
        assertThat(failed.path("scheduled_retry_count").asInt()).isEqualTo(1);
        assertThat(failed.path("scheduled_retry_at").isNull()).isTrue();
        assertThatThrownBy(() -> queue.requeue(jobId, false)).isInstanceOf(Problem.class);
        verify(llm, times(4)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void retryAfterPreventsEarlyDispatchUntilItsStoredTime() {
        long jobId = exhaust("llm_rate_limited", 7200.0);
        ObjectNode failed = jobs.get(jobId);
        assertThat(Instant.parse(failed.path("scheduled_retry_at").asText()))
                .isEqualTo(Instant.parse(failed.path("finished_at").asText()).plusSeconds(7200));
        assertThat(retries.requeueDue("ollama")).isZero();
        assertThat(jobs.get(jobId).path("max_attempts").asInt()).isEqualTo(3);
        database.update("UPDATE analysis_jobs SET scheduled_retry_at=now()-interval '1 second' WHERE id=?", jobId);
        assertThat(retries.requeueDue("ollama")).isEqualTo(1);
        verify(llm, times(3)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void retryAfterLongerThanOneDayNeverGetsAutomaticallyRequeued() {
        long jobId = exhaust("llm_rate_limited", 86401.0);
        assertThat(jobs.get(jobId).path("scheduled_retry_at").isNull()).isTrue();
        assertThat(retries.requeueDue("ollama")).isZero();
        assertThat(worker.once(jobId).path("status").asText()).isEqualTo("idle");
        verify(llm, times(3)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void retryAfterExactlyOneDayIsAcceptedWithoutAnEarlyDispatch() {
        long jobId = exhaust("llm_rate_limited", 86400.0);
        ObjectNode failed = jobs.get(jobId);
        assertThat(Instant.parse(failed.path("scheduled_retry_at").asText()))
                .isEqualTo(Instant.parse(failed.path("finished_at").asText()).plusSeconds(86400));
        assertThat(retries.requeueDue("ollama")).isZero();
        assertThat(failed.path("scheduled_retry_count").asInt()).isZero();
        verify(llm, times(3)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void nonTransientAndUncertainFailuresAreExcludedEvenWhenExhausted() {
        List<String> errors = List.of("llm_auth", "llm_quota", "llm_request", "llm_response",
                "invalid_result", "invalid_snapshot", "prompt_file_missing", "prompt_hash_mismatch",
                "llm_timeout", "llm_connection", "unexpected_error", "interrupted");
        for (String errorCode : errors) {
            long jobId = newJob();
            database.update("UPDATE analysis_jobs SET attempt_count=2 WHERE id=?", jobId);
            doThrow(new LlmFailure(errorCode)).when(llm)
                    .generate(anyString(), anyString(), anyString(), anyString());
            assertThat(worker.once(jobId).path("status").asText()).isEqualTo("failed");
            assertThat(jobs.get(jobId).path("scheduled_retry_at").isNull()).isTrue();
        }
        assertThat(retries.requeueDue("ollama")).isZero();
        assertThat(database.one("SELECT count(*) AS total FROM analysis_jobs WHERE status='failed' AND attempt_count=3")
                .path("total").asInt()).isEqualTo(errors.size());
        verify(llm, times(errors.size())).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void futureOrWrongProviderReservationsAreNotSelected() {
        long jobId = exhaust("llm_server_error", null);
        assertThat(retries.requeueDue("deepseek")).isZero();
        assertThat(retries.requeueDue("openai")).isZero();
        assertThat(jobs.get(jobId).path("status").asText()).isEqualTo("failed");
        assertThat(retries.requeueDue("ollama")).isEqualTo(1);
        verify(llm, times(3)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void concurrentSchedulersGrantOnlyOneAdditionalAttempt() throws Exception {
        long jobId = exhaust("llm_server_error", null);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> scans = new ArrayList<>();
            for (int schedulerIndex = 0; schedulerIndex < 8; schedulerIndex++) {
                scans.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return retries.requeueDue("ollama");
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int requeued = 0;
            for (Future<Integer> scan : scans) requeued += scan.get(20, TimeUnit.SECONDS);
            assertThat(requeued).isEqualTo(1);
            ObjectNode pending = jobs.get(jobId);
            assertThat(pending.path("attempt_count").asInt()).isEqualTo(3);
            assertThat(pending.path("max_attempts").asInt()).isEqualTo(4);
            assertThat(pending.path("scheduled_retry_count").asInt()).isEqualTo(1);
            verify(llm, times(3)).generate(anyString(), anyString(), anyString(), anyString());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void scheduledAttemptKeepsWorkerLockAndTokenProtection() throws Exception {
        long jobId = exhaust("llm_server_error", null);
        retries.requeueDue("ollama");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(llm).generate(anyString(), anyString(), anyString(), anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ObjectNode> analysis = executor.submit(() -> worker.once(jobId));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.once(jobId).path("status").asText()).isEqualTo("idle");
            assertThat(retries.requeueDue("ollama")).isZero();
            database.update("UPDATE analysis_jobs SET started_at=now()-interval '6 minutes' WHERE id=?", jobId);
            assertThatThrownBy(() -> queue.requeue(jobId, true)).isInstanceOf(Problem.class)
                    .extracting("code").isEqualTo("execution_busy");
            release.countDown();
            assertThat(analysis.get(20, TimeUnit.SECONDS).path("status").asText()).isEqualTo("succeeded");
            verify(llm, times(4)).generate(anyString(), anyString(), anyString(), anyString());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptedFourthAttemptDoesNotResetBudgetDuringManualRecovery() {
        long jobId = exhaust("llm_server_error", null);
        retries.requeueDue("ollama");
        try (JobQueue.Claim claim = queue.claim("ollama", jobId)) {
            database.update("UPDATE analysis_jobs SET started_at=now()-interval '6 minutes' WHERE id=?", jobId);
        }
        queue.requeue(jobId, true);
        ObjectNode recovered = jobs.get(jobId);
        assertThat(recovered.path("status").asText()).isEqualTo("failed");
        assertThat(recovered.path("error_code").asText()).isEqualTo("interrupted");
        assertThat(recovered.path("attempt_count").asInt()).isEqualTo(4);
        assertThat(recovered.path("scheduled_retry_count").asInt()).isEqualTo(1);
        assertThat(recovered.path("scheduled_retry_at").isNull()).isTrue();
        assertThat(retries.requeueDue("ollama")).isZero();
        assertThat(worker.once(jobId).path("status").asText()).isEqualTo("idle");
        verify(llm, times(3)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void skippedExecutionLocksDoNotBlockLaterBatches() {
        for (int jobIndex = 0; jobIndex < 105; jobIndex++) newJob();
        database.update("UPDATE analysis_jobs SET status='failed',attempt_count=3,error_code='llm_server_error',"
                + "finished_at=now(),next_attempt_at=NULL,scheduled_retry_at=now()");
        List<ExecutionLocks.Lease> leases = new ArrayList<>();
        try {
            for (ObjectNode job : database.rows("SELECT execution_lock_key FROM analysis_jobs ORDER BY id LIMIT 100")) {
                leases.add(executionLocks.acquire(job.path("execution_lock_key").asText()));
            }
            assertThat(retries.requeueDue("ollama")).isEqualTo(5);
            assertThat(database.one("SELECT count(*) AS total FROM analysis_jobs WHERE scheduled_retry_count=1")
                    .path("total").asInt()).isEqualTo(5);
        } finally {
            for (ExecutionLocks.Lease lease : leases) lease.close();
        }
        assertThat(retries.requeueDue("ollama")).isEqualTo(100);
        assertThat(retries.requeueDue("ollama")).isZero();
        assertThat(database.one("SELECT count(*) AS total FROM analysis_jobs WHERE status='pending'"
                + " AND attempt_count=3 AND max_attempts=4 AND scheduled_retry_count=1").path("total").asInt())
                .isEqualTo(105);
        verifyNoInteractions(llm, github);
    }

    @Test
    void repeatedEventsKeepTheSameJobAndDoNotResetScheduledBudget() {
        long jobId = exhaust("llm_server_error", null);
        JsonNode originalPayload = database.one("SELECT payload FROM error_events WHERE id="
                + "(SELECT representative_event_id FROM analysis_jobs WHERE id=?)", jobId).path("payload");
        ObjectNode snapshot = snapshot(jobId);
        JsonNode reservation = jobs.get(jobId).path("scheduled_retry_at");
        ObjectNode anotherEvent = originalPayload.deepCopy();
        anotherEvent.put("event_id", "another-occurrence");
        ErrorEvent event = ErrorEvent.read(anotherEvent);
        assertThat(jobs.enqueue(event, evidence.forEvent(event)).path("job_id").asLong()).isEqualTo(jobId);
        assertThat(jobs.get(jobId).path("scheduled_retry_at")).isEqualTo(reservation);
        retries.requeueDue("ollama");
        anotherEvent.put("event_id", "after-scheduled-requeue");
        ErrorEvent repeated = ErrorEvent.read(anotherEvent);
        assertThat(jobs.enqueue(repeated, evidence.forEvent(repeated)).path("job_id").asLong()).isEqualTo(jobId);
        assertThat(jobs.get(jobId).path("scheduled_retry_count").asInt()).isEqualTo(1);
        assertThat(jobs.get(jobId).path("attempt_count").asInt()).isEqualTo(3);
        assertThat(snapshot(jobId)).isEqualTo(snapshot);
        assertThat(database.one("SELECT count(*) AS total FROM analysis_jobs").path("total").asInt()).isEqualTo(1);
        verify(llm, times(3)).generate(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void jobHttpResponseAndOpenApiExposeScheduledRetryFields() throws Exception {
        long jobId = exhaust("llm_server_error", null);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/jobs/" + jobId)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = Json.parse(response.body());
            assertThat(body.path("scheduled_retry_count").asInt()).isZero();
            assertThat(body.path("scheduled_retry_at").isTextual()).isTrue();
            JsonNode schema = Json.parse(Files.readString(Path.of("src/main/resources/openapi.json")))
                    .path("components").path("schemas").path("JobDetails").path("properties");
            assertThat(schema.has("scheduled_retry_count")).isTrue();
            assertThat(schema.has("scheduled_retry_at")).isTrue();
            assertThat(schema.path("max_attempts").path("maximum").asInt()).isEqualTo(4);
        }
    }

    @Test
    void v10BackfillsOnlyEligibleHistoricalFailuresAndPreservesRows() {
        String schema = "before_hourly_retry";
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target("9").locations("classpath:db/migration").load();
        try {
            flyway.migrate();
            database.update("INSERT INTO " + schema + ".error_groups(fingerprint,signature) VALUES ('old','{}')");
            database.update("INSERT INTO " + schema + ".error_events(service,environment,event_id,group_id,payload,payload_sha256)"
                    + " VALUES ('order-service','local','old',1,'{}','old')");
            for (String errorCode : List.of("llm_server_error", "llm_rate_limited", "invalid_result")) {
                database.update("INSERT INTO " + schema + ".analysis_jobs(group_id,representative_event_id,basis_hash,basis,"
                        + "specification,bdd,prompt_file,result_schema,status,attempt_count,error_code,finished_at,next_attempt_at)"
                        + " VALUES(1,1,?,'{}','{}','{}','bdd-document-analysis-v1.txt','{}','failed',3,?,now(),NULL)",
                        errorCode, errorCode);
            }
            database.update("INSERT INTO " + schema + ".analysis_jobs(group_id,representative_event_id,basis_hash,basis,"
                    + "specification,bdd,prompt_file,result_schema,status,attempt_count,result,next_attempt_at)"
                    + " VALUES(1,1,'success','{}','{}','{}','bdd-document-analysis-v1.txt','{}','succeeded',1,'{\"preserved\":true}',NULL)");
            List<ObjectNode> before = database.rows("SELECT to_jsonb(job) AS row FROM " + schema + ".analysis_jobs job ORDER BY id");
            Flyway.configure().configuration(flyway.getConfiguration()).target("latest").load().migrate();
            List<ObjectNode> after = database.rows("SELECT to_jsonb(job) AS row FROM " + schema + ".analysis_jobs job ORDER BY id");
            for (int rowIndex = 0; rowIndex < before.size(); rowIndex++) {
                ObjectNode migrated = (ObjectNode) after.get(rowIndex).path("row");
                assertThat(migrated.path("scheduled_retry_count").asInt()).isZero();
                assertThat(migrated.path("scheduled_retry_at").isNull()).isEqualTo(rowIndex >= 2);
                migrated.remove(List.of("scheduled_retry_count", "scheduled_retry_at"));
                assertThat(migrated).isEqualTo(before.get(rowIndex).path("row"));
            }
            database.update("UPDATE " + schema + ".analysis_jobs SET max_attempts=4,scheduled_retry_count=1 WHERE id=1");
            assertThatThrownBy(() -> database.update("UPDATE " + schema + ".analysis_jobs SET max_attempts=5 WHERE id=1"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> database.update("UPDATE " + schema + ".analysis_jobs SET scheduled_retry_count=2 WHERE id=1"))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            database.update("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private long newJob() {
        String marker = UUID.randomUUID().toString();
        ErrorEvent event = ErrorEvent.read(input.deepCopy().put("event_id", marker).put("deploy_sha", marker));
        return jobs.enqueue(event, evidence.forEvent(event)).path("job_id").asLong();
    }

    private long exhaust(String errorCode, Double finalRetryAfter) {
        long jobId = newJob();
        for (int attemptNumber = 1; attemptNumber <= 3; attemptNumber++) {
            doThrow(new LlmFailure(errorCode, attemptNumber == 3 ? finalRetryAfter : null)).when(llm)
                    .generate(anyString(), anyString(), anyString(), anyString());
            assertThat(worker.once(jobId).path("status").asText())
                    .isEqualTo(attemptNumber < 3 ? "retry_scheduled" : "failed");
            if (attemptNumber < 3) database.update(
                    "UPDATE analysis_jobs SET next_attempt_at=now()-interval '1 second' WHERE id=?", jobId);
        }
        return jobId;
    }

    private ObjectNode snapshot(long jobId) {
        return database.one("SELECT representative_event_id,basis_hash,basis,specification,bdd,prompt_file,"
                + "result_schema,condition_hash,business_context,test_evidence FROM analysis_jobs WHERE id=?", jobId);
    }
}
