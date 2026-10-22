package com.example1.bddllm.job;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.analysis.TestEvidencePolicy;
import com.example1.bddllm.evidence.TestEvidenceService;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.event.Grouping;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.example1.bddllm.analysis.PromptCatalog;
import com.example1.bddllm.analysis.AiProviderRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class JobService {
    private final EventRegistrationRepository registrations;
    private final AnalysisJobRepository jobs;
    private final AiProviderRegistry ai;
    private final PromptCatalog prompts;
    private final TestEvidenceService testEvidence;

    public JobService(
        EventRegistrationRepository registrations,
        AnalysisJobRepository jobs,
        AiProviderRegistry ai,
        TestEvidenceService testEvidence,
        PromptCatalog prompts) {
        this.registrations = registrations;
        this.jobs = jobs;
        this.ai = ai;
        this.prompts = prompts;
        this.testEvidence = testEvidence;
    }

    public String provider() {
        return ai.selectedProvider();
    }

    public String model() {
        return ai.selectedModel();
    }

    public PromptCatalog.Template promptTemplate(boolean withTests) {
        return prompts.select(withTests);
    }

    public String prompt() {
        return promptTemplate(false).content();
    }

    public ObjectNode enqueue(ErrorEvent errorEvent, ObjectNode docs) {
        registrations.checkEventIdentity(errorEvent);
        PromptCatalog.Template template = promptTemplate(docs.has("test_source_config"));
        String prompt = template.content();
        ObjectNode registrationDocs = docs.deepCopy();
        ObjectNode basis;
        if (docs.has("test_source_config")) {
            ObjectNode snapshot = testEvidence.collect(errorEvent, docs, prompt);
            registrationDocs.set("test_evidence", snapshot);
            basis = TestEvidencePolicy.basis(errorEvent, docs.get("specification"), docs.get("bdd"), prompt, provider(), model(), snapshot);
        } else {
            basis = AnalysisPolicy.basis(errorEvent, docs.get("specification"), docs.get("bdd"), prompt, provider(), model());
        }
        basis.put("prompt_file", template.fileName());
        String hash = AnalysisPolicy.basisHash(basis),
            fingerprint = Grouping.fingerprint(errorEvent),
            condition = errorEvent.conditionHash();
        EventRegistrationRepository.Registration registration =
            registrations.register(errorEvent, registrationDocs, template.fileName(), basis, hash);
        return Json.obj(
            "group_id",
            registration.groupId(),
            "event_row_id",
            registration.eventRowId(),
            "fingerprint",
            fingerprint,
            "event_created",
            registration.eventCreated(),
            "occurrence_count",
            registration.occurrenceCount(),
            "job_id",
            registration.jobId(),
            "job_created",
            registration.jobCreated(),
            "job_status",
            registration.status(),
            "basis_hash",
            hash,
            "condition_hash",
            condition);
    }

    private ObjectNode storedResult(String raw, String tests) {
        try {
            return tests == null ? AnalysisPolicy.result(Json.parse(raw), null, null)
                : TestEvidencePolicy.result(Json.parse(raw), null, null, Json.parse(tests));
        } catch (Exception exception) {
            throw new Problem(500, "invalid_stored_result", "저장된 분석 결과의 형식이 올바르지 않습니다.");
        }
    }

    @Transactional(readOnly = true)
    public ObjectNode evidence(long id) {
        if (id < 1) throw Problem.invalid();
        AnalysisJob job = jobs.findById(id).orElseThrow(Problem::missing);
        ObjectNode response = Json.obj("job_id", id, "specification", Json.parse(job.specification), "bdd", Json.parse(job.bdd));
        response.set("test_evidence", job.testEvidence == null ? com.fasterxml.jackson.databind.node.NullNode.instance
            : TestEvidencePolicy.input(Json.parse(job.testEvidence)));
        return response;
    }

    @Transactional(readOnly = true)
    public ObjectNode get(long id) {
        if (id < 1) throw Problem.invalid();
        AnalysisJob analysisJob = jobs.findById(id).orElseThrow(Problem::missing);
        ObjectNode jobResponse =
            Json.obj(
                "job_id",
                analysisJob.id,
                "group_id",
                analysisJob.groupId,
                "status",
                analysisJob.status,
                "attempt_count",
                analysisJob.attemptCount,
                "max_attempts",
                analysisJob.maxAttempts,
                "next_attempt_at",
                analysisJob.nextAttemptAt == null ? null : analysisJob.nextAttemptAt.toString(),
                "scheduled_retry_count",
                analysisJob.scheduledRetryCount,
                "scheduled_retry_at",
                analysisJob.scheduledRetryAt == null ? null : analysisJob.scheduledRetryAt.toString(),
                "started_at",
                analysisJob.startedAt == null ? null : analysisJob.startedAt.toString(),
                "finished_at",
                analysisJob.finishedAt == null ? null : analysisJob.finishedAt.toString(),
                "error_code",
                analysisJob.errorCode,
                "result",
                analysisJob.result == null ? null : storedResult(analysisJob.result, analysisJob.testEvidence),
                "feature",
                analysisJob.feature,
                "condition_hash",
                analysisJob.conditionHash,
                "review_required",
                true,
                "coverage_scope",
                analysisJob.testEvidence == null ? "bdd_document_only" : "bdd_and_selected_tests");
        if (analysisJob.promptFile != null) jobResponse.put("prompt_file", analysisJob.promptFile);
        if (analysisJob.testEvidence != null) jobResponse.set("test_evidence", TestEvidencePolicy.summary(Json.parse(analysisJob.testEvidence)));
        JsonNode businessContext = Json.parse(analysisJob.businessContext);
        if (!businessContext.isEmpty()) jobResponse.set("business_context", businessContext);
        return jobResponse;
    }
}
