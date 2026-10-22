package com.example1.bddllm.evidence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SourceSnapshotRepository extends JpaRepository<SourceSnapshot, String> {
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO source_snapshots(cache_key,repository,commit_sha,documents) "
        + "VALUES (:key,:repository,:sha,cast(:documents as jsonb)) ON CONFLICT(cache_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("repository") String repository,
                       @Param("sha") String sha, @Param("documents") String documents);
}
