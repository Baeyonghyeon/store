package com.example1.bddllm.github;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GitHubIssueRepository extends JpaRepository<GitHubIssueDelivery, Long> {
    Optional<GitHubIssueDelivery> findByJobIdAndCandidateNo(long jobId, int candidateNo);
    List<GitHubIssueDelivery> findByJobId(long jobId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from GitHubIssueDelivery delivery where delivery.id=:id")
    Optional<GitHubIssueDelivery> findLocked(long id);
    @Query("select delivery from GitHubIssueDelivery delivery where delivery.state in ('pending','processing','uncertain')"
        + " and delivery.nextAttemptAt<=:now and delivery.attemptCount<3 order by delivery.nextAttemptAt,delivery.id")
    List<GitHubIssueDelivery> findDue(Instant now, Pageable page);
}
