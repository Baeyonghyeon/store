package com.example1.bddllm.worker;

import com.example1.bddllm.common.Problem;
import com.example1.bddllm.common.Transactions;
import com.example1.bddllm.job.AnalysisJob;
import com.example1.bddllm.job.AnalysisJobRepository;
import com.example1.bddllm.job.JobStateRepository;
import com.example1.bddllm.job.ScheduledRetryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ScheduledRetryService {
    private static final int BATCH_SIZE = 100;
    private final Transactions transactions;
    private final ScheduledRetryRepository scheduledRetries;
    private final AnalysisJobRepository jobs;
    private final JobStateRepository states;
    private final ExecutionLocks executionLocks;

    public ScheduledRetryService(Transactions transactions,
                                 ScheduledRetryRepository scheduledRetries,
                                 AnalysisJobRepository jobs,
                                 JobStateRepository states,
                                 ExecutionLocks executionLocks) {
        this.transactions = transactions;
        this.scheduledRetries = scheduledRetries;
        this.jobs = jobs;
        this.states = states;
        this.executionLocks = executionLocks;
    }

    public int requeueDue(String provider) {
        long afterId = 0;
        int requeued = 0;
        while (true) {
            long previousId = afterId;
            Batch batch = transactions.required(() -> requeueBatch(provider, previousId));
            if (batch.lastId() == previousId) return requeued;
            afterId = batch.lastId();
            requeued += batch.requeued();
        }
    }

    private Batch requeueBatch(String provider, long afterId) {
        List<AnalysisJob> candidates = scheduledRetries.lockDue(provider, afterId, BATCH_SIZE);
        Instant now = states.now();
        long lastId = afterId;
        int requeued = 0;
        for (AnalysisJob job : candidates) {
            lastId = job.id;
            ExecutionLocks.Lease lease;
            try {
                lease = executionLocks.acquire(job.executionLockKey.toString());
            } catch (Problem failure) {
                if (failure.code.equals("execution_busy")) continue;
                throw failure;
            }
            try (lease) {
                job.scheduledRetryCount++;
                job.maxAttempts++;
                job.status = "pending";
                job.claimToken = null;
                job.startedAt = null;
                job.finishedAt = null;
                job.scheduledRetryAt = null;
                job.nextAttemptAt = now;
                jobs.flush();
                requeued++;
            }
        }
        return new Batch(lastId, requeued);
    }

    private record Batch(long lastId, int requeued) {}
}
