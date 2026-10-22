package com.example1.bddllm.worker;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.event.ErrorEventEntity;
import com.example1.bddllm.event.ErrorGroup;
import com.example1.bddllm.job.AnalysisJob;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class JobSnapshot {
    private JobSnapshot() {
    }

    public static ObjectNode from(AnalysisJob job, ErrorEventEntity event, ErrorGroup group) {
        if (event.groupId != job.groupId)
            throw new IllegalStateException("representative_event_missing");
        ObjectNode snapshot = Json.obj(
            "basis",
            Json.parse(job.basis),
            "basis_hash",
            job.basisHash,
            "result_schema",
            Json.parse(job.resultSchema),
            "prompt",
            job.prompt,
            "specification",
            Json.parse(job.specification),
            "bdd",
            Json.parse(job.bdd),
            "payload",
            Json.parse(event.payload),
            "fingerprint",
            group.fingerprint,
            "condition_hash",
            job.conditionHash,
            "business_context",
            Json.parse(job.businessContext),
            "event_service",
            event.service,
            "event_environment",
            event.environment,
            "event_identifier",
            event.eventId);
        if (job.promptFile != null) snapshot.put("prompt_file", job.promptFile);
        if (job.testEvidence != null) snapshot.set("test_evidence", Json.parse(job.testEvidence));
        return snapshot;
    }
}
