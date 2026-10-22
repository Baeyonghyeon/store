package com.example1.bddllm.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ErrorGroupRepository extends JpaRepository<ErrorGroup, Long> {
    Optional<ErrorGroup> findByFingerprint(String fingerprint);

    @Query("select coalesce(sum(g.occurrenceCount),0) from ErrorGroup g")
    long totalOccurrences();
}
