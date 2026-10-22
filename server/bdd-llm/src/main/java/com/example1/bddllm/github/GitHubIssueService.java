package com.example1.bddllm.github;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.common.Transactions;
import com.example1.bddllm.job.AnalysisJobRepository;
import com.example1.bddllm.review.CandidateReviewEntity;
import com.example1.bddllm.review.CandidateReviewEntityRepository;
import com.example1.bddllm.worker.ExecutionLocks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class GitHubIssueService {
    private final GitHubIssueRepository deliveries;
    private final GitHubIssueSettings settings;
    private final GitHubIssueClient client;
    private final AnalysisJobRepository jobs;
    private final CandidateReviewEntityRepository reviews;
    private final Transactions transactions;
    private final ExecutionLocks locks;
    public GitHubIssueService(GitHubIssueRepository deliveries, GitHubIssueSettings settings, GitHubIssueClient client,
        AnalysisJobRepository jobs, CandidateReviewEntityRepository reviews, Transactions transactions, ExecutionLocks locks) {
        this.deliveries = deliveries; this.settings = settings; this.client = client;
        this.jobs = jobs; this.reviews = reviews; this.transactions = transactions; this.locks = locks;
    }
    // Called inside the review transaction while the job row is locked. No network I/O.
    public void reviewChanged(long jobId, int candidateNo, int revision, String action, boolean fromSlack,
                              ObjectNode detail, JsonNode candidate) {
        GitHubIssueDelivery delivery = deliveries.findByJobIdAndCandidateNo(jobId, candidateNo).orElse(null);
        boolean approve = action.equals("approve") && fromSlack && settings.enabled && settings.service.equals(detail.path("service").asText());
        if (!approve) {
            if (delivery != null) {
                boolean unknownCreate = delivery.issueNumber == null && Set.of("processing", "uncertain").contains(delivery.state);
                delivery.state = unknownCreate ? "uncertain" : "cancelled";
                delivery.nextAttemptAt = unknownCreate ? Instant.now() : null;
                deliveries.save(delivery);
            }
            return;
        }
        if (delivery == null) {
            delivery = new GitHubIssueDelivery();
            delivery.jobId = jobId; delivery.candidateNo = candidateNo;
            delivery.repository = settings.repository; delivery.marker = UUID.randomUUID();
            delivery.createdAt = Instant.now(); delivery.state = "pending";
        }
        boolean unknownCreate = delivery.issueNumber == null && Set.of("processing", "uncertain").contains(delivery.state);
        delivery.reviewRevision = revision;
        delivery.title = GitHubIssueBody.title(candidate);
        delivery.body = GitHubIssueBody.body(detail, candidate, candidateNo, revision, delivery.marker);
        delivery.attemptCount = 0;
        delivery.state = unknownCreate ? "uncertain" : "pending";
        delivery.errorCode = settings.token.isBlank() ? "github_not_configured" : null;
        delivery.nextAttemptAt = Instant.now();
        if (Json.length(delivery.body) > 60000) {
            delivery.state = "failed"; delivery.errorCode = "github_body_too_large"; delivery.nextAttemptAt = null;
        }
        deliveries.saveAndFlush(delivery);
    }
    public List<GitHubIssueDelivery> forJob(long jobId) { return deliveries.findByJobId(jobId); }
    public static ObjectNode summary(GitHubIssueDelivery delivery) {
        return Json.obj("state", delivery.state, "repository", delivery.repository, "number", delivery.issueNumber,
            "url", delivery.issueUrl, "review_revision", delivery.reviewRevision, "published_revision", delivery.publishedRevision,
            "error_code", delivery.errorCode, "attempt_count", delivery.attemptCount, "version", delivery.version);
    }
    private CandidateReviewEntity latest(long jobId, int candidateNo) {
        return reviews.findByJobIdOrderByCandidateNoAscRevisionAsc(jobId).stream()
            .filter(review -> review.candidateNo == candidateNo).reduce((previous, current) -> current).orElse(null);
    }
    private boolean approved(GitHubIssueDelivery delivery) {
        CandidateReviewEntity latest = latest(delivery.jobId, delivery.candidateNo);
        return latest != null && latest.action.equals("approve") && latest.revision == delivery.reviewRevision;
    }
    public record Claim(long id, long jobId, int candidateNo, int revision, String repository, UUID marker,
                        Long issueNumber, String title, String body, Instant createdAt, boolean reconcile) {}
    private Claim claim(long id, long jobId) {
        return transactions.required(() -> {
            jobs.findLockedById(jobId).orElseThrow(Problem::missing);
            GitHubIssueDelivery delivery = deliveries.findLocked(id).orElseThrow(Problem::missing);
            if (!Set.of("pending", "processing", "uncertain").contains(delivery.state)
                || delivery.nextAttemptAt == null || delivery.nextAttemptAt.isAfter(Instant.now()) || delivery.attemptCount >= 3) return null;
            boolean reconcile = delivery.issueNumber == null && Set.of("processing", "uncertain").contains(delivery.state);
            if (!approved(delivery) && !reconcile) { delivery.state = "cancelled"; delivery.nextAttemptAt = null; return null; }
            if (!settings.repository.equals(delivery.repository)) {
                delivery.errorCode = "github_target_changed"; delivery.nextAttemptAt = null;
                delivery.state = reconcile ? "uncertain" : "failed"; return null;
            }
            delivery.state = "processing"; delivery.attemptCount++;
            delivery.nextAttemptAt = Instant.now();
            deliveries.flush();
            return new Claim(delivery.id, delivery.jobId, delivery.candidateNo, delivery.reviewRevision, delivery.repository,
                delivery.marker, delivery.issueNumber, delivery.title, delivery.body, delivery.createdAt, reconcile);
        });
    }
    public ObjectNode dispatchOnce() {
        if (!settings.enabled || settings.token.isBlank()) return Json.obj("status", "not_configured");
        for (GitHubIssueDelivery candidate : deliveries.findDue(Instant.now(), PageRequest.of(0, 10))) {
            try (ExecutionLocks.Lease lease = locks.acquire(candidate.marker.toString())) {
                Claim claim = claim(candidate.id, candidate.jobId);
                if (claim == null) continue;
                try {
                    GitHubIssueClient.Receipt receipt = claim.reconcile()
                        ? client.reconcile(claim.repository(), claim.marker(), claim.createdAt())
                        : client.publish(claim.repository(), claim.issueNumber(), claim.title(), claim.body(), claim.revision());
                    if (receipt == null) fail(claim, new GitHubIssueClient.Failure("github_result_not_found", true, true, 30));
                    else complete(claim, receipt);
                } catch (GitHubIssueClient.Failure failure) { fail(claim, failure); }
                return Json.obj("status", "processed", "job_id", claim.jobId(), "candidate_no", claim.candidateNo());
            } catch (Problem problem) {
                if (!problem.code.equals("execution_busy")) throw problem;
            }
        }
        return Json.obj("status", "idle");
    }
    private void complete(Claim claim, GitHubIssueClient.Receipt receipt) {
        transactions.required(() -> {
            jobs.findLockedById(claim.jobId()).orElseThrow(Problem::missing);
            GitHubIssueDelivery delivery = deliveries.findLocked(claim.id()).orElseThrow(Problem::missing);
            delivery.issueNumber = receipt.number(); delivery.issueUrl = receipt.url();
            delivery.publishedRevision = receipt.revision(); delivery.errorCode = null;
            if (!approved(delivery)) { delivery.state = "cancelled"; delivery.nextAttemptAt = null; }
            else if (delivery.reviewRevision == receipt.revision()) { delivery.state = "published"; delivery.nextAttemptAt = null; }
            else { delivery.state = "pending"; delivery.nextAttemptAt = Instant.now(); delivery.attemptCount = 0; }
            deliveries.flush();
            return null;
        });
    }
    private void fail(Claim claim, GitHubIssueClient.Failure failure) {
        transactions.required(() -> {
            jobs.findLockedById(claim.jobId()).orElseThrow(Problem::missing);
            GitHubIssueDelivery delivery = deliveries.findLocked(claim.id()).orElseThrow(Problem::missing);
            boolean uncertain = delivery.issueNumber == null && (claim.reconcile() || failure.uncertain);
            // A newer approval may already have queued another version while this request was in flight.
            if (delivery.reviewRevision != claim.revision() && !uncertain) return null;
            delivery.state = uncertain ? "uncertain" : failure.retryable ? "pending" : "failed";
            delivery.errorCode = failure.code;
            boolean retry = delivery.attemptCount < 3 && (uncertain || failure.retryable);
            delivery.nextAttemptAt = retry ? Instant.now().plusSeconds(failure.delaySeconds) : null;
            if (!retry && !uncertain) delivery.state = "failed";
            deliveries.flush();
            return null;
        });
    }
    public ObjectNode retry(long jobId, int candidateNo) {
        return transactions.required(() -> {
            jobs.findLockedById(jobId).orElseThrow(Problem::missing);
            GitHubIssueDelivery found = deliveries.findByJobIdAndCandidateNo(jobId, candidateNo).orElseThrow(Problem::missing);
            try (ExecutionLocks.Lease lease = locks.acquire(found.marker.toString())) {
                GitHubIssueDelivery delivery = deliveries.findLocked(found.id).orElseThrow(Problem::missing);
                if (!Set.of("failed", "uncertain").contains(delivery.state) || !approved(delivery))
                    throw new Problem(409, "github_issue_not_retryable", "최신 승인 상태와 Issue 등록 상태를 확인하세요.");
                delivery.state = delivery.state.equals("uncertain") ? "uncertain" : "pending";
                delivery.attemptCount = 0; delivery.nextAttemptAt = Instant.now(); delivery.errorCode = null;
                deliveries.flush(); return summary(delivery);
            }
        });
    }
}
