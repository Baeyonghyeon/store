package com.example1.bddllm.job;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class JobStateRepository {
    @PersistenceContext
    private EntityManager entityManager;

    public AnalysisJob next(String provider, Long target) {
        List<AnalysisJob> rows =
            entityManager
                .unwrap(Session.class)
                .createNativeQuery(
                    "SELECT * FROM analysis_jobs WHERE status='pending' AND"
                        + " next_attempt_at<=now() AND attempt_count<max_attempts AND"
                        + " COALESCE(basis->>'provider','openai')=:provider AND"
                        + " (cast(:target as bigint) IS NULL OR id=:target) ORDER BY"
                        + " next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    AnalysisJob.class)
                .setParameter("provider", provider)
                .setParameter("target", target)
                .getResultList();
        if (rows.isEmpty()) return null;
        AnalysisJob job = rows.getFirst();
        entityManager.refresh(job);
        return job;
    }

    public Instant now() {
        return entityManager
            .unwrap(Session.class)
            .createNativeQuery("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class)
            .getSingleResult()
            .toInstant();
    }

    public boolean owns(long id, UUID token) {
        return entityManager
            .createQuery(
                "select count(j) from AnalysisJob j where j.id=:id and"
                    + " j.status='running' and j.claimToken=:token",
                Long.class)
            .setParameter("id", id)
            .setParameter("token", token)
            .getSingleResult()
            == 1L;
    }

    public int complete(long id, UUID token, String result, String feature) {
        int count =
            entityManager
                .createQuery(
                    "update AnalysisJob j set"
                        + " j.status='succeeded',j.result=:result,j.feature=:feature,j.finishedAt=:finished,j.errorCode=null,j.nextAttemptAt=null,j.scheduledRetryAt=null"
                        + " where j.id=:id and j.status='running' and"
                        + " j.claimToken=:token")
                .setParameter("result", result)
                .setParameter("feature", feature)
                .setParameter("finished", now())
                .setParameter("id", id)
                .setParameter("token", token)
                .executeUpdate();
        entityManager.clear();
        return count;
    }
}
