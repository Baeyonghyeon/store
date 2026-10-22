package com.example1.bddllm.job;

import com.example1.bddllm.worker.ScheduledRetryPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ScheduledRetryRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public List<AnalysisJob> lockDue(String provider, long afterId, int batchSize) {
        return entityManager.unwrap(Session.class)
                .createNativeQuery(
                        "SELECT * FROM analysis_jobs WHERE status='failed'"
                                + " AND error_code IN (:errors) AND scheduled_retry_count=0"
                                + " AND scheduled_retry_at<=now() AND attempt_count=max_attempts"
                                + " AND max_attempts<4 AND COALESCE(basis->>'provider','openai')=:provider"
                                + " AND id>:afterId ORDER BY id LIMIT :batchSize FOR UPDATE SKIP LOCKED",
                        AnalysisJob.class)
                .setParameter("errors", ScheduledRetryPolicy.RETRYABLE_ERRORS)
                .setParameter("provider", provider)
                .setParameter("afterId", afterId)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }
}
