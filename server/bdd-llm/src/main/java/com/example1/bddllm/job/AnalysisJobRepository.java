package com.example1.bddllm.job;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {
    Optional<AnalysisJob> findByGroupIdAndConditionHashAndBasisHash(
        long groupId, String conditionHash, String basisHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AnalysisJob j where j.id=:id")
    Optional<AnalysisJob> findLockedById(long id);

    @Query(
        "select j from AnalysisJob j where j.status='succeeded' and (:before is null or"
            + " j.id<:before) order by j.id desc")
    List<AnalysisJob> findReviewJobs(Long before, Pageable page);

    interface StatusCount {
        String getStatus();

        long getCount();
    }

    @Query("select j.status as status,count(j) as count from AnalysisJob j group by j.status")
    List<StatusCount> countByStatus();
}
