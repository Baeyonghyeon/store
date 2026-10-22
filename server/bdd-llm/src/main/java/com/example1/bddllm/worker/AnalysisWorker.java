package com.example1.bddllm.worker;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.analysis.PromptCatalog;
import com.example1.bddllm.analysis.TestEvidencePolicy;
import com.example1.bddllm.analysis.LlmFailure;
import com.example1.bddllm.analysis.LlmGateway;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.event.Grouping;
import com.example1.bddllm.job.JobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.springframework.stereotype.Service;

@Service
public class AnalysisWorker {
    private final JobQueue queue;
    private final JobService jobs;
    private final LlmGateway llm;
    private final PromptCatalog prompts;
    private final String diagnostics;

    public AnalysisWorker(JobQueue queue, JobService jobs, LlmGateway llm, PromptCatalog prompts,
                          @org.springframework.beans.factory.annotation.Value("${bdd.invalid-result-directory:}") String diagnostics) {
        this.queue = queue;
        this.jobs = jobs;
        this.llm = llm;
        this.prompts = prompts;
        this.diagnostics = diagnostics;
    }

    public ObjectNode once(Long target) {
        llm.validateConfiguration(jobs.provider());
        try (JobQueue.Claim claim = queue.claim(jobs.provider(), target)) {
            if (claim == null)
                return Json.obj(
                    "job_id",
                    null,
                    "status",
                    "idle",
                    "error_code",
                    null,
                    "next_attempt_at",
                    null);
            queue.assertOwner(claim);
            ObjectNode result;
            try {
                ObjectNode jobSnapshot = claim.snapshot();
                if (jobSnapshot.hasNonNull("prompt_file")) {
                    String fileName = jobSnapshot.path("prompt_file").asText();
                    if (jobSnapshot.hasNonNull("prompt") || !fileName.equals(jobSnapshot.path("basis").path("prompt_file").asText()))
                        throw new LlmFailure("invalid_snapshot");
                    PromptCatalog.Template template = prompts.resolve(fileName, jobSnapshot.path("basis").path("prompt_sha256").asText());
                    jobSnapshot.put("prompt", template.content());
                }
                ErrorEvent event;
                try {
                    event = validateSnapshot(jobSnapshot);
                } catch (Exception exception) {
                    throw new LlmFailure("invalid_snapshot");
                }
                JsonNode analysisBasis = jobSnapshot.path("basis");
                result =
                    llm.generate(
                        analysisBasis.path("provider").asText("openai"),
                        analysisBasis.path("model").asText(),
                        jobSnapshot.path("prompt").asText(),
                        jobSnapshot.has("test_evidence") ? TestEvidencePolicy.evidence(event, jobSnapshot.path("specification"), jobSnapshot.path("bdd"), jobSnapshot.path("test_evidence"))
                            : AnalysisPolicy.evidence(event, jobSnapshot.path("specification"), jobSnapshot.path("bdd")));
                try {
                    if (jobSnapshot.has("test_evidence")) TestEvidencePolicy.result(result,
                        jobSnapshot.path("specification").path("content").asText(), jobSnapshot.path("bdd").path("content").asText(), jobSnapshot.path("test_evidence"));
                    else
                        AnalysisPolicy.result(result, jobSnapshot.path("specification").path("content").asText(), jobSnapshot.path("bdd").path("content").asText());
                } catch (Problem exception) {
                    captureInvalidResult(diagnostics, claim.id(), result);
                    throw new LlmFailure("invalid_result");
                }
            } catch (LlmFailure llmFailure) {
                return queue.fail(claim, llmFailure.code, llmFailure.retryAfter);
            } catch (Exception exception) {
                return queue.fail(claim, "unexpected_error", null);
            }
            // Saving failure leaves running: it must not be mistaken for an analysis failure and
            // re-called.
            queue.complete(claim, result, AnalysisPolicy.feature(result));
            return Json.obj(
                "job_id",
                claim.id(),
                "status",
                "succeeded",
                "error_code",
                null,
                "next_attempt_at",
                null);
        }
    }

    /**
     * Opt-in local diagnostics: no API keys, no result saved as a successful DB analysis.
     */
    public static void captureInvalidResult(String directory, long jobId, ObjectNode result) {
        if (directory.isBlank()) return;
        try {
            java.nio.file.Path root = java.nio.file.Path.of(directory);
            if (!root.isAbsolute()) throw new IllegalArgumentException("diagnostics_directory_must_be_absolute");
            java.nio.file.Files.createDirectories(root);
            java.nio.file.Files.writeString(root.resolve("job-" + jobId + "-invalid-result.json"), Json.pretty(result));
        } catch (java.io.IOException | IllegalArgumentException error) {
            org.slf4j.LoggerFactory.getLogger(AnalysisWorker.class).warn("invalid_result_diagnostic_write_failed");
        }
    }

    public static ErrorEvent validateSnapshot(ObjectNode jobSnapshot) {
        ErrorEvent errorEvent = ErrorEvent.read(jobSnapshot.path("payload"));
        ObjectNode analysisBasis = jobSnapshot.path("basis").deepCopy();
        analysisBasis.putIfAbsent("provider", TextNode.valueOf("openai"));
        boolean tests = analysisBasis.path("analysis_version").asText().equals("2");
        if (tests != jobSnapshot.has("test_evidence")) throw new IllegalStateException("invalid_snapshot_version");
        ObjectNode expected = tests ? TestEvidencePolicy.basis(errorEvent, jobSnapshot.path("specification"), jobSnapshot.path("bdd"),
            jobSnapshot.path("prompt").asText(), analysisBasis.path("provider").asText(), analysisBasis.path("model").asText(), jobSnapshot.path("test_evidence"))
            : AnalysisPolicy.basis(
            errorEvent,
            jobSnapshot.path("specification"),
            jobSnapshot.path("bdd"),
            jobSnapshot.path("prompt").asText(),
            analysisBasis.path("provider").asText(),
            analysisBasis.path("model").asText());
        if (jobSnapshot.hasNonNull("prompt_file")) expected.put("prompt_file", jobSnapshot.path("prompt_file").asText());
        if (!analysisBasis.equals(expected)
            || !AnalysisPolicy.basisHash(analysisBasis).equals(jobSnapshot.path("basis_hash").asText())
            || !jobSnapshot.path("result_schema").equals(tests ? TestEvidencePolicy.SCHEMA : AnalysisPolicy.SCHEMA)
            || !Grouping.fingerprint(errorEvent).equals(jobSnapshot.path("fingerprint").asText())
            || !errorEvent.conditionHash().equals(jobSnapshot.path("condition_hash").asText())
            || !errorEvent.context().equals(jobSnapshot.path("business_context"))
            || !errorEvent.service().equals(jobSnapshot.path("event_service").asText())
            || !errorEvent.payload().path("environment").equals(jobSnapshot.path("event_environment"))
            || !errorEvent.payload().path("event_id").equals(jobSnapshot.path("event_identifier")))
            throw new IllegalStateException("invalid_snapshot");
        return errorEvent;
    }
}
