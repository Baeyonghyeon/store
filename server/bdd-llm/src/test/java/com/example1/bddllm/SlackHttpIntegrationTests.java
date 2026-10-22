package com.example1.bddllm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example1.bddllm.analysis.LlmGateway;
import com.example1.bddllm.github.GitHubIssueClient;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.evidence.EvidenceRegistry;
import com.example1.bddllm.job.JobService;
import com.example1.bddllm.review.ReviewService;
import com.example1.bddllm.slack.SlackChannelLockRepository;
import com.example1.bddllm.slack.SlackService;
import com.example1.bddllm.worker.AnalysisWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slack.api.app_backend.SlackSignature;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

@ActiveProfiles("slack")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "bdd.test-migrate=true",
            "bdd.role=slack",
            "bdd.slack.poll-enabled=false",
            "bdd.test-lock-marker=true"
        })
@Import(DatabaseFixture.class)
class SlackHttpIntegrationTests {
    static final String SECRET = "fixture-signing-secret";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static HttpServer slack;
    static final List<ObjectNode> calls = new CopyOnWriteArrayList<>();
    static final AtomicLong sequence = new AtomicLong();
    static volatile boolean ambiguous;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry properties) throws Exception {
        POSTGRES.start();
        properties.add("bdd.github-issues.service", () -> "order-service");
        properties.add("bdd.github-issues.token", () -> "fixture-token");
        properties.add("bdd.locks", () -> Path.of("build/isolated-test-locks").toAbsolutePath().toString());
        slack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slack.createContext(
                "/",
                exchange -> {
                    String method = exchange.getRequestURI().getPath().substring(1);
                    ObjectNode payload =
                            (ObjectNode)
                                    Json.parse(
                                            new String(
                                                    exchange.getRequestBody().readAllBytes(),
                                                    StandardCharsets.UTF_8));
                    calls.add(Json.obj("method", method, "body", payload));
                    if (ambiguous && method.equals("chat.postMessage")) {
                        exchange.close();
                        return;
                    }
                    ObjectNode out =
                            Json.obj(
                                    "ok",
                                    true,
                                    "team_id",
                                    "T1",
                                    "channel",
                                    "C1",
                                    "ts",
                                    "1000." + sequence.incrementAndGet(),
                                    "view",
                                    Json.obj("id", "V1"));
                    byte[] bytes = Json.encode(out).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        slack.start();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("SLACK_API_BASE_URL", () -> "http://127.0.0.1:" + slack.getAddress().getPort());
        properties.add("SLACK_BOT_TOKEN", () -> "xoxb-fixture");
        properties.add("SLACK_APP_TOKEN", () -> "xapp-fixture");
        properties.add("SLACK_SIGNING_SECRET", () -> SECRET);
        properties.add("SLACK_CHANNEL_ID", () -> "C1");
        properties.add("SLACK_REVIEWER_IDS", () -> "U1");
    }

    @Autowired DatabaseFixture db;
    @Autowired JobService jobs;
    @Autowired AnalysisWorker worker;
    @Autowired ReviewService reviews;
    @Autowired SlackService service;
    @Autowired SlackChannelLockRepository channelLocks;
    @MockitoBean LlmGateway llm;
    @MockitoBean GitHubIssueClient github;
    @LocalServerPort int port;

    @BeforeEach
    void resetDatabase() throws Exception {
        when(github.publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt())).thenAnswer(invocation ->
            new GitHubIssueClient.Receipt(88,"https://github.com/Baeyonghyeon/noteu/issues/88",invocation.getArgument(4)));
        ambiguous = false;
        calls.clear();
        db.update(
                "TRUNCATE"
                    + " slack_notifications,candidate_reviews,analysis_jobs,error_events,error_groups"
                    + " RESTART IDENTITY CASCADE");
        ObjectNode result =
                (ObjectNode)
                        Json.parse(
                                Files.readString(
                                        Path.of(
                                                "samples/evaluation/coupon_missing/reference.json")));
        when(llm.generate(anyString(), anyString(), anyString(), anyString())).thenReturn(result);
    }

    @AfterAll
    static void stopServer() {
        slack.stop(0);
    }

    long prepare() throws Exception {
        ErrorEvent event =
                ErrorEvent.read(
                        Json.parse(Files.readString(Path.of("samples/coupon_expired/event.json"))));
        Path root = Path.of("evidence/order-service/orders").toRealPath();
        ObjectNode docs =
                Json.obj(
                        "specification",
                        EvidenceRegistry.document(root, "specification.md"),
                        "bdd",
                        EvidenceRegistry.document(root, "existing.feature"));
        long id = jobs.enqueue(event, docs).path("job_id").asLong();
        assertThat(worker.once(id).path("status").asText()).isEqualTo("succeeded");
        service.pollOnce();
        return id;
    }

    ObjectNode last(String method) {
        return calls.stream()
                .filter(apiCall -> apiCall.path("method").asText().equals(method))
                .reduce((previousCall, currentCall) -> currentCall)
                .orElseThrow()
                .path("body")
                .deepCopy();
    }

    String messageMetadata() {
        ObjectNode body = last("chat.postMessage");
        for (JsonNode block : body.path("blocks"))
            if (block.path("type").asText().equals("actions"))
                return block.path("elements").get(0).path("value").asText();
        throw new AssertionError();
    }

    HttpResponse<String> send(JsonNode payload, String secret, long timestamp) throws Exception {
        String raw = "payload=" + URLEncoder.encode(Json.encode(payload), StandardCharsets.UTF_8);
        String ts = Long.toString(timestamp);
        String signature = new SlackSignature.Generator(secret).generate(ts, raw);
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/slack/events"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-Slack-Request-Timestamp", ts)
                                .header("X-Slack-Signature", signature)
                                .POST(HttpRequest.BodyPublishers.ofString(raw))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    ObjectNode action(long id, String action, String user) {
        ObjectNode record = db.one("SELECT message_ts FROM slack_notifications WHERE job_id=?", id);
        return Json.obj(
                "type",
                "block_actions",
                "team",
                Json.obj("id", "T1"),
                "user",
                Json.obj("id", user),
                "channel",
                Json.obj("id", "C1"),
                "message",
                Json.obj("ts", record.path("message_ts")),
                "trigger_id",
                "trigger-fixture",
                "actions",
                List.of(Json.obj("action_id", "bdd_" + action, "value", messageMetadata())));
    }

    void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("asynchronous Slack operation not completed");
    }

    ObjectNode open(long id, String action) throws Exception {
        calls.removeIf(apiCall -> apiCall.path("method").asText().equals("views.update"));
        HttpResponse<String> response =
                send(action(id, action, "U1"), SECRET, Instant.now().getEpochSecond());
        assertThat(response.statusCode()).isEqualTo(200);
        await(
                () ->
                        calls.stream()
                                .anyMatch(
                                        apiCall ->
                                                apiCall.path("method").asText().equals("views.update")
                                                        && apiCall.path("body")
                                                                .path("view")
                                                                .path("callback_id")
                                                                .asText()
                                                                .equals("bdd_review_submit")));
        return last("views.update").path("view").deepCopy();
    }

    ObjectNode submission(ObjectNode view, String reason, String title) {
        view.put("id", "V1");
        ObjectNode values = Json.obj();
        for (JsonNode block : view.path("blocks"))
            if (block.path("type").asText().equals("input")) {
                String name = block.path("block_id").asText();
                String value =
                        name.equals("reason")
                                ? reason
                                : name.equals("title") && title != null
                                        ? title
                                        : block.path("element").path("initial_value").asText();
                values.set(name, Json.obj("value", Json.obj("value", value)));
            }
        view.set("state", Json.obj("values", values));
        return Json.obj(
                "type",
                "view_submission",
                "team",
                Json.obj("id", "T1"),
                "user",
                Json.obj("id", "U1"),
                "view",
                view);
    }

    @Test
    void invalidSignatureAndStaleTimestampAreRejected() throws Exception {
        long id = prepare();
        ObjectNode actionPayload = action(id, "approve", "U1");
        assertThat(send(actionPayload, "wrong-secret", Instant.now().getEpochSecond()).statusCode())
                .isEqualTo(401);
        assertThat(send(actionPayload, SECRET, Instant.now().minusSeconds(360).getEpochSecond()).statusCode())
                .isEqualTo(401);
        assertThat(db.one("SELECT count(*) n FROM candidate_reviews").path("n").asInt()).isZero();
    }

    @Test
    void buttonsEditRejectApproveAndHistoryOverRealHttp() throws Exception {
        long id = prepare();
        int revision = 0;
        for (String action : List.of("edit", "reject", "approve")) {
            ObjectNode view = open(id, action);
            ObjectNode payload =
                    submission(
                            view,
                            action.equals("approve") ? "" : "검토 사유",
                            action.equals("edit") ? "Slack에서 수정한 후보" : null);
            long start = System.nanoTime();
            HttpResponse<String> response = send(payload, SECRET, Instant.now().getEpochSecond());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(3));
            assertThat(Json.parse(response.body()).path("response_action").asText())
                    .isEqualTo("update");
            int expected = ++revision;
            await(
                    () ->
                            reviews.detail(id).path("candidates").get(0).path("revision").asInt()
                                    == expected);
            service.pollOnce();
            assertThat(send(payload, SECRET, Instant.now().getEpochSecond()).statusCode())
                    .isEqualTo(200);
            Thread.sleep(100);
            assertThat(reviews.detail(id).path("candidates").get(0).path("revision").asInt())
                    .isEqualTo(expected);
            // The refreshed Slack message contains the new revision metadata.
            ObjectNode refreshed = last("chat.update");
            calls.add(Json.obj("method", "chat.postMessage", "body", refreshed));
        }
        ObjectNode reviewDetail = reviews.detail(id);
        assertThat(reviewDetail.path("candidates").get(0).path("history")).hasSize(3);
        assertThat(reviews.approved(id)).contains("Slack에서 수정한 후보");
        assertThat(reviewDetail.path("candidates").get(0).path("github_issue").path("url").asText()).endsWith("/issues/88");
        assertThat(last("chat.update").toString()).contains("bdd_github_issue_link");
        verify(github,times(1)).publish(anyString(),isNull(),anyString(),anyString(),eq(3));
        assertThat(
                        db.one("SELECT sent_revision FROM slack_notifications WHERE job_id=?", id)
                                .path("sent_revision")
                                .asInt())
                .isEqualTo(3);
    }

    @Test
    void repeatedNotificationPollDoesNotPostAgain() throws Exception {
        prepare();
        service.pollOnce();
        service.pollOnce();
        assertThat(
                        calls.stream()
                                .filter(apiCall -> apiCall.path("method").asText().equals("chat.postMessage"))
                                .count())
                .isEqualTo(1);
    }

    @Test
    void unknownDeliveryIsNotAutomaticallyReposted() throws Exception {
        ambiguous = true;
        prepare();
        assertThat(db.one("SELECT state FROM slack_notifications").path("state").asText())
                .isEqualTo("uncertain");
        long attempts =
                calls.stream()
                        .filter(apiCall -> apiCall.path("method").asText().equals("chat.postMessage"))
                        .count();
        assertThat(attempts).isEqualTo(1);
        service.pollOnce();
        assertThat(
                        calls.stream()
                                .filter(apiCall -> apiCall.path("method").asText().equals("chat.postMessage"))
                                .count())
                .isEqualTo(attempts);
    }

    @Test
    void unauthorizedReviewerCannotOpenForm() throws Exception {
        long id = prepare();
        assertThat(
                        send(action(id, "approve", "U2"), SECRET, Instant.now().getEpochSecond())
                                .statusCode())
                .isEqualTo(200);
        Thread.sleep(150);
        assertThat(
                        calls.stream()
                                .filter(apiCall -> apiCall.path("method").asText().equals("views.open"))
                                .count())
                .isZero();
    }

    @Test
    void dedicatedJpaSessionKeepsAndReleasesChannelLock() {
        assertThatThrownBy(() -> channelLocks.acquire("T1", "C1"))
                .hasMessageContaining("slack_service_already_running");
        try (SlackChannelLockRepository.Lease lease = channelLocks.acquire("T2", "C2")) {
            assertThat(lease.isValid()).isTrue();
            assertThat(lease.isValid()).isTrue();
            assertThatThrownBy(() -> channelLocks.acquire("T2", "C2"))
                    .hasMessageContaining("slack_service_already_running");
            assertThatThrownBy(() -> channelLocks.resetRetryable("T2", "C2", 1, 1))
                    .isInstanceOf(Problem.class);
        }
        try (SlackChannelLockRepository.Lease lease = channelLocks.acquire("T2", "C2")) {
            assertThat(lease.isValid()).isTrue();
        }
    }
}
