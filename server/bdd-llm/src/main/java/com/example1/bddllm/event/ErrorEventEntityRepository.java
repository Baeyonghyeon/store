package com.example1.bddllm.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ErrorEventEntityRepository extends JpaRepository<ErrorEventEntity, Long> {
    Optional<ErrorEventEntity> findByServiceAndEnvironmentAndEventId(
        String service, String environment, String eventId);
}
