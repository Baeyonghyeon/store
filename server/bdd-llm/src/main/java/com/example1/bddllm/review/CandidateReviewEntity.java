package com.example1.bddllm.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "candidate_reviews")
public class CandidateReviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "job_id")
    public long jobId;

    @Column(name = "candidate_no")
    public int candidateNo;

    @Column(name = "revision")
    public int revision;

    @Column(name = "action", columnDefinition = "text")
    public String action;

    @Column(name = "reviewer", columnDefinition = "text")
    public String reviewer;

    @Column(name = "reason", columnDefinition = "text")
    public String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate", columnDefinition = "jsonb")
    public String candidate;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", updatable = false)
    public Instant createdAt;
}
