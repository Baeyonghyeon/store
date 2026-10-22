package com.example1.bddllm.event;

import com.example1.bddllm.common.ApiErrors;
import com.example1.bddllm.common.DatabaseHealthRepository;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.evidence.EvidenceRegistry;
import com.example1.bddllm.job.JobService;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@ConditionalOnProperty(name = "bdd.role", havingValue = "api", matchIfMissing = true)
public class EventController {
    private final JobService jobs;
    private final EvidenceRegistry evidence;
    private final DatabaseHealthRepository health;

    public EventController(
        JobService jobs, EvidenceRegistry evidence, DatabaseHealthRepository health) {
        this.jobs = jobs;
        this.evidence = evidence;
        this.health = health;
    }

    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> event(@RequestBody String body) {
        ErrorEvent errorEvent = ErrorEvent.read(Json.parse(body));
        ObjectNode registrationResult = jobs.enqueue(errorEvent, evidence.forEvent(errorEvent));
        return ResponseEntity.status(registrationResult.path("job_created").asBoolean() ? 201 : 200)
            .location(URI.create("/jobs/" + registrationResult.path("job_id").asLong()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(Json.encode(registrationResult));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<String> job(@PathVariable long id) {
        return ApiErrors.json(200, jobs.get(id));
    }

    @GetMapping("/jobs/{id}/evidence")
    public ResponseEntity<String> jobEvidence(@PathVariable long id) {
        return ApiErrors.json(200, jobs.evidence(id));
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        health.check();
        return ApiErrors.json(200, Json.obj("status", "ok"));
    }
}
