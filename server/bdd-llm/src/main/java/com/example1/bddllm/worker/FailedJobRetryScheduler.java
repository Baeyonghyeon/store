package com.example1.bddllm.worker;

import com.example1.bddllm.job.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class FailedJobRetryScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FailedJobRetryScheduler.class);
    private final ScheduledRetryService retries;
    private final JobService jobs;

    public FailedJobRetryScheduler(ScheduledRetryService retries, JobService jobs) {
        this.retries = retries;
        this.jobs = jobs;
        LOGGER.info("Hourly failed-job retry enabled: cron=0 0 * * * *, zone=Asia/Seoul, additional_attempts=1");
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Seoul")
    public void retryFailedJobs() {
        try {
            String provider = jobs.provider();
            int requeued = retries.requeueDue(provider);
            LOGGER.info("Hourly failed-job retry: provider={}, requeued={}", provider, requeued);
        } catch (RuntimeException failure) {
            LOGGER.error("Hourly failed-job retry failed; the next scheduled scan will try again", failure);
        }
    }
}
