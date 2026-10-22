package com.example1.bddllm.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "analysis_jobs")
public class AnalysisJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "group_id")
    public long groupId;

    @Column(name = "representative_event_id")
    public long representativeEventId;

    @Column(name = "basis_hash", columnDefinition = "text")
    public String basisHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "basis", columnDefinition = "jsonb")
    public String basis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "specification", columnDefinition = "jsonb")
    public String specification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bdd", columnDefinition = "jsonb")
    public String bdd;

    @Column(name = "prompt", columnDefinition = "text")
    public String prompt;

    @Column(name = "prompt_file", columnDefinition = "text")
    public String promptFile;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_schema", columnDefinition = "jsonb")
    public String resultSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "test_evidence", columnDefinition = "jsonb")
    public String testEvidence;

    @Column(name = "status", columnDefinition = "text")
    public String status;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "claim_token")
    public UUID claimToken;

    @Column(name = "attempt_count")
    public int attemptCount;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "finished_at")
    public Instant finishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    public String result;

    @Column(name = "feature", columnDefinition = "text")
    public String feature;

    @Column(name = "error_code", columnDefinition = "text")
    public String errorCode;

    @Column(name = "max_attempts")
    public int maxAttempts;

    @Column(name = "next_attempt_at")
    public Instant nextAttemptAt;

    @Column(name = "scheduled_retry_count")
    public int scheduledRetryCount;

    @Column(name = "scheduled_retry_at")
    public Instant scheduledRetryAt;

    @Column(name = "execution_lock_key")
    public UUID executionLockKey;

    @Column(name = "condition_hash", columnDefinition = "text")
    public String conditionHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "business_context", columnDefinition = "jsonb")
    public String businessContext;
}
