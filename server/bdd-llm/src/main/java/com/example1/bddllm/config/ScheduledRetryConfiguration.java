package com.example1.bddllm.config;

import com.example1.bddllm.job.JobService;
import com.example1.bddllm.worker.FailedJobRetryScheduler;
import com.example1.bddllm.worker.ScheduledRetryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bdd.role", havingValue = "worker")
@ConditionalOnExpression("!${bdd.once:false} && ${bdd.scheduled-retry.enabled:true}")
@EnableScheduling
public class ScheduledRetryConfiguration {
    @Bean
    public FailedJobRetryScheduler failedJobRetryScheduler(ScheduledRetryService retries,
                                                           JobService jobs) {
        return new FailedJobRetryScheduler(retries, jobs);
    }
}
