package com.example1.bddllm.github;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "github_issue_deliveries")
public class GitHubIssueDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "job_id") public long jobId;
    @Column(name = "candidate_no") public int candidateNo;
    @Column(columnDefinition = "text") public String repository;
    public UUID marker;
    @Column(name = "review_revision") public int reviewRevision;
    @Column(name = "published_revision") public int publishedRevision;
    @Column(columnDefinition = "text") public String title;
    @Column(columnDefinition = "text") public String body;
    @Column(columnDefinition = "text") public String state;
    @Column(name = "issue_number") public Long issueNumber;
    @Column(name = "issue_url", columnDefinition = "text") public String issueUrl;
    @Column(name = "attempt_count") public int attemptCount;
    @Column(name = "next_attempt_at") public Instant nextAttemptAt;
    @Column(name = "error_code", columnDefinition = "text") public String errorCode;
    @Column(name = "created_at") public Instant createdAt;
    @Version public long version;
}
