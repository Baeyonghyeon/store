package com.example1.bddllm.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "source_snapshots")
public class SourceSnapshot {
    @Id @Column(name = "cache_key", columnDefinition = "text") public String cacheKey;
    @Column(columnDefinition = "text") public String repository;
    @Column(name = "commit_sha", columnDefinition = "text") public String commitSha;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") public String documents;
}
