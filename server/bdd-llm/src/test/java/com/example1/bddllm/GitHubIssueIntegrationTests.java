package com.example1.bddllm;

import com.example1.bddllm.analysis.LlmGateway;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.evidence.EvidenceRegistry;
import com.example1.bddllm.github.GitHubIssueClient;
import com.example1.bddllm.github.GitHubIssueService;
import com.example1.bddllm.job.JobService;
import com.example1.bddllm.review.ReviewService;
import com.example1.bddllm.worker.AnalysisWorker;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"bdd.test-migrate=true", "bdd.role=api", "bdd.github-issues.service=order-service",
    "bdd.github-issues.token=fixture-token", "bdd.test-lock-marker=true"})
@Import(DatabaseFixture.class)
class GitHubIssueIntegrationTests {
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        POSTGRES.start();
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("bdd.locks", () -> Path.of("build/github-test-locks").toAbsolutePath().toString());
    }
    @Autowired DatabaseFixture db;
    @Autowired JobService jobs;
    @Autowired EvidenceRegistry evidence;
    @Autowired AnalysisWorker worker;
    @Autowired ReviewService reviews;
    @Autowired GitHubIssueService issues;
    @MockitoBean LlmGateway llm;
    @MockitoBean GitHubIssueClient github;
    long jobId;

    @BeforeEach void prepare() throws Exception {
        db.update("TRUNCATE slack_notifications,candidate_reviews,analysis_jobs,error_events,error_groups RESTART IDENTITY CASCADE");
        ObjectNode result = (ObjectNode) Json.parse(Files.readString(Path.of("samples/evaluation/coupon_missing/reference.json")));
        when(llm.generate(anyString(),anyString(),anyString(),anyString())).thenReturn(result);
        ErrorEvent event = ErrorEvent.read(Json.parse(Files.readString(Path.of("samples/coupon_expired/event.json"))));
        jobId = jobs.enqueue(event, evidence.forEvent(event)).path("job_id").asLong();
        worker.once(jobId);
        when(github.publish(anyString(), nullable(Long.class), anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new GitHubIssueClient.Receipt(88, "https://github.com/Baeyonghyeon/noteu/issues/88", invocation.getArgument(4));
        });
    }
    ObjectNode command(String action, String title) {
        ObjectNode current = reviews.detail(jobId).withArray("candidates").get(0).deepCopy();
        ObjectNode command = Json.obj("action", action, "reviewer", "slack:T1:U1", "reason", action.equals("approve")?"":"검토 의견",
            "expected_revision", current.path("revision"));
        if (action.equals("edit")) { ObjectNode candidate = current.withObject("current").deepCopy(); candidate.put("title", title); command.set("candidate",candidate); }
        return command;
    }
    void approve() { reviews.submitFromSlack(jobId,1,command("approve",null)); }
    ObjectNode delivery() { return db.one("SELECT state,issue_number,issue_url,review_revision,published_revision,attempt_count,error_code,body FROM github_issue_deliveries WHERE job_id=?",jobId); }
    void ready() { db.update("UPDATE github_issue_deliveries SET next_attempt_at=now() WHERE job_id=?",jobId); }
    @Test void slackApprovalQueuesThenPublishesAndReusesIssueRecord() {
        approve();
        assertThat(delivery().path("state").asText()).isEqualTo("pending");
        verifyNoInteractions(github);
        issues.dispatchOnce(); issues.dispatchOnce();
        ObjectNode issue = reviews.detail(jobId).path("candidates").get(0).withObject("github_issue");
        assertThat(issue.path("state").asText()).isEqualTo("published");
        assertThat(issue.path("url").asText()).endsWith("/issues/88");
        assertThat(delivery().path("body").asText()).contains("```gherkin", "Given", "명세 근거", "bdd-gap-issue:", "승인 수정본 1");
        assertThatThrownBy(this::approve).isInstanceOf(Problem.class);
        verify(github,times(1)).publish(anyString(),isNull(),anyString(),anyString(),eq(1));
        verify(llm,times(1)).generate(anyString(),anyString(),anyString(),anyString());
    }
    @Test void webApprovalDoesNotEnqueueEvenWithSlackReviewerText() {
        reviews.submit(jobId,1,command("approve",null));
        assertThat(delivery()).isNull();
        verifyNoInteractions(github);
    }
    @Test void rejectedPendingApprovalNeverPublishes() {
        approve();
        reviews.submitFromSlack(jobId,1,command("reject",null));
        issues.dispatchOnce();
        assertThat(delivery().path("state").asText()).isEqualTo("cancelled");
        verifyNoInteractions(github);
    }
    @Test void reapprovedEditUpdatesSameIssue() {
        approve(); issues.dispatchOnce();
        reviews.submitFromSlack(jobId,1,command("edit","수정한 시나리오"));
        approve(); issues.dispatchOnce();
        verify(github).publish(anyString(),eq(88L),contains("수정한 시나리오"),anyString(),eq(3));
        assertThat(delivery().path("published_revision").asInt()).isEqualTo(3);
        assertThat(db.one("SELECT count(*) n FROM github_issue_deliveries").path("n").asInt()).isEqualTo(1);
    }
    @Test void authenticationFailurePreservesApprovalAndCanRetry() {
        when(github.publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt()))
            .thenThrow(new GitHubIssueClient.Failure("github_auth",false,false,30));
        approve(); issues.dispatchOnce();
        assertThat(delivery().path("state").asText()).isEqualTo("failed");
        assertThat(reviews.detail(jobId).path("candidates").get(0).path("status").asText()).isEqualTo("approved");
        issues.retry(jobId,1);
        doReturn(new GitHubIssueClient.Receipt(88,"https://github.com/Baeyonghyeon/noteu/issues/88",1))
            .when(github).publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt());
        issues.dispatchOnce();
        assertThat(delivery().path("state").asText()).isEqualTo("published");
    }
    @Test void rateLimitRetriesAreBounded() {
        when(github.publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt()))
            .thenThrow(new GitHubIssueClient.Failure("github_rate_limited",false,true,60));
        approve();
        for (int attempt=0;attempt<3;attempt++) { ready(); issues.dispatchOnce(); }
        assertThat(delivery().path("state").asText()).isEqualTo("failed");
        assertThat(delivery().path("attempt_count").asInt()).isEqualTo(3);
        issues.dispatchOnce();
        verify(github,times(3)).publish(anyString(),isNull(),anyString(),anyString(),anyInt());
    }
    @Test void ambiguousCreateReconcilesInsteadOfPostingAgain() {
        when(github.publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt()))
            .thenThrow(new GitHubIssueClient.Failure("github_delivery_unknown",true,false,30));
        approve(); issues.dispatchOnce();
        assertThat(delivery().path("state").asText()).isEqualTo("uncertain");
        when(github.reconcile(anyString(),any(),any())).thenReturn(new GitHubIssueClient.Receipt(88,"https://github.com/Baeyonghyeon/noteu/issues/88",1));
        ready(); issues.dispatchOnce();
        assertThat(delivery().path("state").asText()).isEqualTo("published");
        verify(github,times(1)).publish(anyString(),isNull(),anyString(),anyString(),anyInt());
        verify(github).reconcile(anyString(),any(),any());
    }
    @Test void missingReconciliationResultNeverAutomaticallyRecreates() {
        when(github.publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt()))
            .thenThrow(new GitHubIssueClient.Failure("github_delivery_unknown",true,false,30));
        approve(); issues.dispatchOnce();
        for(int attempt=0;attempt<4;attempt++) { ready(); issues.dispatchOnce(); }
        assertThat(delivery().path("state").asText()).isEqualTo("uncertain");
        verify(github,times(1)).publish(anyString(),isNull(),anyString(),anyString(),anyInt());
        verify(github,times(2)).reconcile(anyString(),any(),any());
    }
    @Test void interruptedProcessingIsRecoveredThroughReconciliation() {
        approve();
        db.update("UPDATE github_issue_deliveries SET state='processing',attempt_count=1 WHERE job_id=?",jobId);
        when(github.reconcile(anyString(),any(),any())).thenReturn(new GitHubIssueClient.Receipt(88,"https://github.com/Baeyonghyeon/noteu/issues/88",1));
        issues.dispatchOnce();
        assertThat(delivery().path("state").asText()).isEqualTo("published");
        verify(github,never()).publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt());
    }
    @Test void twoPublishersCannotSendSameCandidateConcurrently() throws Exception {
        approve();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();
            return new GitHubIssueClient.Receipt(88,"https://github.com/Baeyonghyeon/noteu/issues/88",1);
        }).when(github).publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ObjectNode> first = executor.submit(issues::dispatchOnce);
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(issues.dispatchOnce().path("status").asText()).isEqualTo("idle");
            release.countDown(); first.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); }
        verify(github,times(1)).publish(anyString(),isNull(),anyString(),anyString(),anyInt());
    }
    @Test void editWhileRequestIsInFlightKeepsKnownIssueButWaitsForApproval() {
        approve();
        doAnswer(invocation -> {
            reviews.submitFromSlack(jobId,1,command("edit","전송 중 수정"));
            return new GitHubIssueClient.Receipt(88,"https://github.com/Baeyonghyeon/noteu/issues/88",1);
        }).when(github).publish(anyString(),nullable(Long.class),anyString(),anyString(),anyInt());
        issues.dispatchOnce();
        assertThat(delivery().path("state").asText()).isEqualTo("cancelled");
        assertThat(delivery().path("issue_number").asLong()).isEqualTo(88);
    }
    @Test void newerApprovalDuringCreateQueuesPatchToSameIssue() {
        approve();
        doAnswer(invocation -> {
            reviews.submitFromSlack(jobId,1,command("edit","전송 중 수정")); approve();
            return new GitHubIssueClient.Receipt(88,"https://github.com/Baeyonghyeon/noteu/issues/88",1);
        }).when(github).publish(anyString(),isNull(),anyString(),anyString(),anyInt());
        issues.dispatchOnce();
        assertThat(delivery().path("state").asText()).isEqualTo("pending");
        issues.dispatchOnce();
        assertThat(delivery().path("published_revision").asInt()).isEqualTo(3);
        verify(github).publish(anyString(),eq(88L),anyString(),anyString(),eq(3));
    }
    @Test void changedDestinationNeverSendsToUnexpectedRepository() {
        approve(); db.update("UPDATE github_issue_deliveries SET repository='other/repo' WHERE job_id=?",jobId);
        issues.dispatchOnce();
        assertThat(delivery().path("error_code").asText()).isEqualTo("github_target_changed");
        verifyNoInteractions(github);
    }
}
