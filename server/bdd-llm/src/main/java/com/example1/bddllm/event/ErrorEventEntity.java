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
@Table(name = "error_events")
public class ErrorEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "service", columnDefinition = "text")
    public String service;

    @Column(name = "environment", columnDefinition = "text")
    public String environment;

    @Column(name = "event_id", columnDefinition = "text")
    public String eventId;

    @Column(name = "group_id")
    public long groupId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    public String payload;

    @Column(name = "payload_sha256", columnDefinition = "text")
    public String payloadSha256;

    @Column(name = "created_at")
    public Instant createdAt;
}
