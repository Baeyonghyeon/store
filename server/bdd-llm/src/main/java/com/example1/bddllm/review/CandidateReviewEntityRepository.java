package com.example1.bddllm.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandidateReviewEntityRepository extends JpaRepository<CandidateReviewEntity, Long> {
    List<CandidateReviewEntity> findByJobIdOrderByCandidateNoAscRevisionAsc(long jobId);
}
