package com.example1.bddllm.slack;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "slack_notifications")
public class SlackNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "team_id", columnDefinition = "text")
    public String teamId;

    @Column(name = "channel_id", columnDefinition = "text")
    public String channelId;

    @Column(name = "job_id")
    public long jobId;

    @Column(name = "candidate_no")
    public int candidateNo;

    @Column(name = "state", columnDefinition = "text")
    public String state;

    @Column(name = "sent_revision")
    public int sentRevision;

    @Column(name = "issue_version")
    public long issueVersion;

    @Column(name = "message_ts", columnDefinition = "text")
    public String messageTs;

    @Column(name = "error_code", columnDefinition = "text")
    public String errorCode;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;
}
