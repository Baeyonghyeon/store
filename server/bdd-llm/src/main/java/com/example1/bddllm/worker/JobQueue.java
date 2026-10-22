package com.example1.bddllm.worker;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.common.Transactions;
import com.example1.bddllm.event.ErrorEventEntity;
import com.example1.bddllm.event.ErrorEventEntityRepository;
import com.example1.bddllm.event.ErrorGroup;
import com.example1.bddllm.event.ErrorGroupRepository;
import com.example1.bddllm.job.AnalysisJob;
import com.example1.bddllm.job.AnalysisJobRepository;
import com.example1.bddllm.job.JobStateRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class JobQueue {
    public record Claim(long id, UUID token, ObjectNode snapshot, ExecutionLocks.Lease lease)
        implements AutoCloseable {
        public void close() {
            lease.close();
        }
    }

    private final Transactions transactions;
    private final AnalysisJobRepository jobs;
    private final JobStateRepository states;
    private final ErrorEventEntityRepository events;
    private final ErrorGroupRepository groups;
    private final ExecutionLocks locks;

    public JobQueue(
        Transactions transactions,
        AnalysisJobRepository jobs,
        JobStateRepository states,
        ErrorEventEntityRepository events,
        ErrorGroupRepository groups,
        ExecutionLocks locks) {
        this.transactions = transactions;
        this.jobs = jobs;
        this.states = states;
        this.events = events;
        this.groups = groups;
        this.locks = locks;
    }

    public Claim claim(String provider, Long target) {
        ExecutionLocks.Lease[] held = {null};
        try {
            return transactions.required(
                () -> {
                    AnalysisJob job = states.next(provider, target);
                    if (job == null) return null;
                    try {
                        held[0] = locks.acquire(job.executionLockKey.toString());
                    } catch (Problem error) {
                        if (error.code.equals("execution_busy")) return null;
                        throw error;
                    }
                    UUID token = UUID.randomUUID();
                    job.status = "running";
                    job.claimToken = token;
                    job.attemptCount++;
                    job.startedAt = states.now();
                    job.finishedAt = null;
                    job.nextAttemptAt = null;
                    job.scheduledRetryAt = null;
                    job.errorCode = null;
                    jobs.flush();
                    ErrorEventEntity event =
                        events.findById(job.representativeEventId)
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        "representative_event_missing"));
                    ErrorGroup group = groups.findById(job.groupId).orElseThrow();
                    return new Claim(
                        job.id, token, JobSnapshot.from(job, event, group), held[0]);
                });
        } catch (RuntimeException error) {
            if (held[0] != null) held[0].close();
            throw error;
        }
    }

    public void assertOwner(Claim claim) {
        if (!states.owns(claim.id(), claim.token()))
            throw new Problem(409, "ownership_lost", "작업 실행 소유권이 없습니다.");
    }

    public void complete(Claim claim, ObjectNode result, String feature) {
        transactions.required(
            () -> {
                if (states.complete(claim.id(), claim.token(), Json.encode(result), feature)
                    != 1) throw new Problem(409, "ownership_lost", "결과 저장 소유권이 없습니다.");
                return null;
            });
    }

    private AnalysisJob owned(Claim claim) {
        AnalysisJob job =
            jobs.findLockedById(claim.id())
                .orElseThrow(() -> new Problem(409, "ownership_lost", "작업 실행 소유권이 없습니다."));
        if (!job.status.equals("running") || !Objects.equals(job.claimToken, claim.token()))
            throw new Problem(409, "ownership_lost", "작업 실행 소유권이 없습니다.");
        return job;
    }

    public ObjectNode fail(Claim claim, String code, Double retryAfter) {
        if (!Set.of(
                "invalid_snapshot",
                "prompt_file_missing",
                "prompt_hash_mismatch",
                "llm_error",
                "invalid_result",
                "unexpected_error",
                "llm_auth",
                "llm_quota",
                "llm_rate_limited",
                "llm_server_error",
                "llm_timeout",
                "llm_connection",
                "llm_request",
                "llm_response",
                "interrupted")
            .contains(code)) throw Problem.invalid();
        return transactions.required(
            () -> {
                AnalysisJob job = owned(claim);
                Double delay = delay(job.attemptCount, job.maxAttempts, code, retryAfter);
                Instant now = states.now();
                job.status = delay == null ? "failed" : "pending";
                job.errorCode = code;
                job.finishedAt = now;
                job.claimToken = null;
                job.nextAttemptAt =
                    delay == null
                        ? null
                        : now.plusNanos(Math.round(delay * 1_000_000_000D));
                job.scheduledRetryAt = delay == null
                        ? ScheduledRetryPolicy.eligibleAt(job.attemptCount, job.maxAttempts,
                                job.scheduledRetryCount, code, retryAfter, now)
                        : null;
                jobs.flush();
                return Json.obj(
                    "job_id",
                    job.id,
                    "status",
                    delay == null ? "failed" : "retry_scheduled",
                    "error_code",
                    code,
                    "next_attempt_at",
                    job.nextAttemptAt == null ? null : job.nextAttemptAt.toString());
            });
    }

    public static Double delay(int attempt, int max, String code, Double retryAfter) {
        if (attempt >= max || !ScheduledRetryPolicy.RETRYABLE_ERRORS.contains(code))
            return null;
        double delay = 30 * Math.pow(2, attempt - 1);
        if (retryAfter != null && Double.isFinite(retryAfter) && retryAfter >= 0) {
            if (retryAfter > 86400) return null;
            delay = Math.max(delay, retryAfter);
        }
        return delay;
    }

    public void requeue(long id, boolean recover) {
        AnalysisJob first = jobs.findById(id).orElseThrow(Problem::missing);
        try (ExecutionLocks.Lease lease = locks.acquire(first.executionLockKey.toString())) {
            transactions.required(
                () -> {
                    AnalysisJob job = jobs.findLockedById(id).orElseThrow(Problem::missing);
                    boolean exhausted = job.attemptCount >= job.maxAttempts;
                    Instant now = states.now();
                    boolean stale =
                        job.startedAt != null
                            && !job.startedAt.isAfter(now.minus(Duration.ofMinutes(5)));
                    if (recover
                        ? !job.status.equals("running") || !stale
                        : !job.status.equals("failed") || exhausted)
                        throw new Problem(
                            409, "job_not_retryable", "상태·실행 횟수·복구 대기 시간을 확인해 주세요.");
                    job.status = exhausted ? "failed" : "pending";
                    job.claimToken = null;
                    job.nextAttemptAt = exhausted ? null : now;
                    job.scheduledRetryAt = null;
                    if (!exhausted) job.startedAt = null;
                    job.finishedAt = exhausted ? now : null;
                    job.errorCode = recover ? "interrupted" : null;
                    job.result = null;
                    job.feature = null;
                    jobs.flush();
                    return null;
                });
        }
    }
}
