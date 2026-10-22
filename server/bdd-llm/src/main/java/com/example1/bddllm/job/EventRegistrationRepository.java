package com.example1.bddllm.job;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.event.ErrorEventEntity;
import com.example1.bddllm.event.ErrorEventEntityRepository;
import com.example1.bddllm.event.ErrorGroup;
import com.example1.bddllm.event.ErrorGroupRepository;
import com.example1.bddllm.event.Grouping;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EventRegistrationRepository {
    @PersistenceContext
    private EntityManager entityManager;
    private final ErrorGroupRepository groups;
    private final ErrorEventEntityRepository events;
    private final AnalysisJobRepository jobs;

    public EventRegistrationRepository(
        ErrorGroupRepository groups,
        ErrorEventEntityRepository events,
        AnalysisJobRepository jobs) {
        this.groups = groups;
        this.events = events;
        this.jobs = jobs;
    }

    public record Registration(
        long groupId,
        long eventRowId,
        long jobId,
        String status,
        long occurrenceCount,
        boolean eventCreated,
        boolean jobCreated) {
    }

    @Transactional(readOnly = true)
    public void checkEventIdentity(ErrorEvent event) {
        events.findByServiceAndEnvironmentAndEventId(event.service(), event.payload().path("environment").asText(),
            event.payload().path("event_id").asText()).ifPresent(stored -> {
            if (!stored.payloadSha256.equals(event.payloadHash())) throw Problem.conflict();
        });
    }

    @Transactional
    public Registration register(
        ErrorEvent event, ObjectNode docs, String promptFile, ObjectNode basis, String basisHash) {
        String fingerprint = Grouping.fingerprint(event);
        String condition = event.conditionHash();
        entityManager
            .createNativeQuery(
                "INSERT INTO error_groups(fingerprint,signature) VALUES"
                    + " (:fingerprint,cast(:signature as jsonb)) ON CONFLICT(fingerprint)"
                    + " DO NOTHING")
            .setParameter("fingerprint", fingerprint)
            .setParameter("signature", Json.encode(Grouping.signature(event)))
            .executeUpdate();
        ErrorGroup group = groups.findByFingerprint(fingerprint).orElseThrow();
        boolean eventCreated =
            entityManager
                .createNativeQuery(
                    "INSERT INTO"
                        + " error_events(service,environment,event_id,group_id,payload,payload_sha256)"
                        + " VALUES"
                        + " (:service,:environment,:eventId,:groupId,cast(:payload"
                        + " as jsonb),:hash) ON"
                        + " CONFLICT(service,environment,event_id) DO NOTHING")
                .setParameter("service", event.service())
                .setParameter(
                    "environment", event.payload().path("environment").asText())
                .setParameter("eventId", event.payload().path("event_id").asText())
                .setParameter("groupId", group.id)
                .setParameter("payload", Json.encode(event.payload()))
                .setParameter("hash", event.payloadHash())
                .executeUpdate()
                == 1;
        ErrorEventEntity stored =
            events.findByServiceAndEnvironmentAndEventId(
                    event.service(),
                    event.payload().path("environment").asText(),
                    event.payload().path("event_id").asText())
                .orElseThrow();
        if (stored.groupId != group.id || !stored.payloadSha256.equals(event.payloadHash()))
            throw Problem.conflict();
        if (eventCreated) {
            entityManager
                .createQuery(
                    "update ErrorGroup g set g.occurrenceCount=g.occurrenceCount+1 where"
                        + " g.id=:id")
                .setParameter("id", group.id)
                .executeUpdate();
            entityManager.refresh(group);
        }
        boolean jobCreated =
            entityManager
                .createNativeQuery(
                    "INSERT INTO"
                        + " analysis_jobs(group_id,representative_event_id,basis_hash,basis,specification,bdd,prompt_file,result_schema,condition_hash,business_context,test_evidence)"
                        + " VALUES (:groupId,:eventId,:basisHash,cast(:basis as"
                        + " jsonb),cast(:spec as jsonb),cast(:bdd as"
                        + " jsonb),:promptFile,cast(:schema as"
                        + " jsonb),:condition,cast(:context as jsonb),cast(:tests as jsonb)) ON"
                        + " CONFLICT(group_id,condition_hash,basis_hash) DO"
                        + " NOTHING")
                .setParameter("groupId", group.id)
                .setParameter("eventId", stored.id)
                .setParameter("basisHash", basisHash)
                .setParameter("basis", Json.encode(basis))
                .setParameter("spec", Json.encode(docs.get("specification")))
                .setParameter("bdd", Json.encode(docs.get("bdd")))
                .setParameter("promptFile", promptFile)
                .setParameter("schema", Json.encode(docs.has("test_evidence") ? com.example1.bddllm.analysis.TestEvidencePolicy.SCHEMA : AnalysisPolicy.SCHEMA))
                .setParameter("tests", docs.has("test_evidence") ? Json.encode(docs.get("test_evidence")) : null)
                .setParameter("condition", condition)
                .setParameter("context", Json.encode(event.context()))
                .executeUpdate()
                == 1;
        AnalysisJob job =
            jobs.findByGroupIdAndConditionHashAndBasisHash(group.id, condition, basisHash)
                .orElseThrow();
        return new Registration(
            group.id,
            stored.id,
            job.id,
            job.status,
            group.occurrenceCount,
            eventCreated,
            jobCreated);
    }
}
