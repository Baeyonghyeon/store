package com.example1.bddllm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example1.bddllm.config.ScheduledRetryConfiguration;
import com.example1.bddllm.job.JobService;
import com.example1.bddllm.worker.FailedJobRetryScheduler;
import com.example1.bddllm.worker.ScheduledRetryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

class ScheduledRetryConfigurationTests {
    private ApplicationContextRunner configured(String... properties) {
        return new ApplicationContextRunner().withUserConfiguration(ScheduledRetryConfiguration.class)
                .withBean(ScheduledRetryService.class, () -> mock(ScheduledRetryService.class))
                .withBean(JobService.class, () -> mock(JobService.class))
                .withBean("taskScheduler", TaskScheduler.class, () -> mock(TaskScheduler.class))
                .withPropertyValues(properties);
    }

    @Test
    void longRunningWorkerRegistersExactlyOneHourlyTask() {
        configured("bdd.role=worker").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FailedJobRetryScheduler.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
        });
    }

    @Test
    void apiSlackToolsMigrationAndDefaultStartupNeverRegisterTheScheduler() {
        for (String role : List.of("api", "slack", "tools", "migrate")) {
            configured("bdd.role=" + role).run(context ->
                    assertThat(context).hasNotFailed().doesNotHaveBean(FailedJobRetryScheduler.class));
        }
        configured().run(context -> assertThat(context).doesNotHaveBean(FailedJobRetryScheduler.class));
    }

    @Test
    void oneShotWorkerAndDisabledFeatureNeverRegisterTheScheduler() {
        configured("bdd.role=worker", "bdd.once=true").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(FailedJobRetryScheduler.class));
        configured("bdd.role=worker", "bdd.scheduled-retry.enabled=false").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(FailedJobRetryScheduler.class));
    }

    @Test
    void scanRunsOnTheNextKoreanHourWithoutAnAdditionalHourDelay() throws Exception {
        Scheduled annotation = FailedJobRetryScheduler.class.getMethod("retryFailedJobs").getAnnotation(Scheduled.class);
        assertThat(annotation.cron()).isEqualTo("0 0 * * * *");
        assertThat(annotation.zone()).isEqualTo("Asia/Seoul");
        ZonedDateTime failure = ZonedDateTime.of(2026, 10, 20, 14, 59, 59, 0, ZoneId.of(annotation.zone()));
        assertThat(CronExpression.parse(annotation.cron()).next(failure)).isEqualTo(failure.plusSeconds(1));
        assertThat(CronExpression.parse(annotation.cron()).next(failure.plusSeconds(1))).isEqualTo(failure.plusHours(1).plusSeconds(1));
    }

    @Test
    void schedulerOnlyRequeuesAndCanRunAgainAfterADatabaseFailure() {
        ScheduledRetryService retries = mock(ScheduledRetryService.class);
        JobService jobs = mock(JobService.class);
        when(jobs.provider()).thenReturn("ollama");
        when(retries.requeueDue("ollama")).thenThrow(new IllegalStateException("test database unavailable"))
                .thenReturn(1);
        FailedJobRetryScheduler scheduler = new FailedJobRetryScheduler(retries, jobs);
        assertThatCode(scheduler::retryFailedJobs).doesNotThrowAnyException();
        assertThatCode(scheduler::retryFailedJobs).doesNotThrowAnyException();
        verify(retries, times(2)).requeueDue("ollama");
        verify(jobs, times(2)).provider();
        verifyNoMoreInteractions(jobs, retries);
    }
}
