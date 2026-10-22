package com.example1.bddllm.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "error_groups")
public class ErrorGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "fingerprint", columnDefinition = "text")
    public String fingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signature", columnDefinition = "jsonb")
    public String signature;

    @Column(name = "occurrence_count")
    public long occurrenceCount;

    @Column(name = "created_at")
    public Instant createdAt;
}
