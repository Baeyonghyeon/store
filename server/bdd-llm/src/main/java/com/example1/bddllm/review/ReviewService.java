package com.example1.bddllm.review;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.github.GitHubIssueService;
import com.example1.bddllm.github.GitHubIssueDelivery;
import com.example1.bddllm.analysis.TestEvidencePolicy;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.common.Transactions;
import com.example1.bddllm.event.ErrorGroupRepository;
import com.example1.bddllm.job.AnalysisJob;
import com.example1.bddllm.job.AnalysisJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class ReviewService {
    private final GitHubIssueService issues;
    private final Transactions transactions;
    private final AnalysisJobRepository jobs;
    private final ErrorGroupRepository groups;
    private final CandidateReviewEntityRepository history;

    public ReviewService(
        Transactions transactions,
        AnalysisJobRepository jobs,
        ErrorGroupRepository groups,
        CandidateReviewEntityRepository history,
        GitHubIssueService issues) {
        this.issues = issues;
        this.transactions = transactions;
        this.jobs = jobs;
        this.groups = groups;
        this.history = history;
    }

    private Problem notFound() {
        return new Problem(404, "review_not_found", "해당 분석 작업 또는 후보가 없습니다.");
    }

    private Problem notReady() {
        return new Problem(409, "review_not_ready", "완료된 분석 또는 승인된 후보가 필요합니다.");
    }

    public static Problem conflict() {
        return new Problem(409, "review_conflict", "검토가 변경되었거나 이미 처리되었습니다. 최신 내용을 확인해 주세요.");
    }

    public static Problem invalid() {
        return new Problem(422, "invalid_review", "수정 내용·사유·명세와 BDD 근거를 확인해 주세요.");
    }

    private Problem corrupt() {
        return new Problem(500, "invalid_stored_review", "저장된 분석 또는 검토 기록의 형식을 확인할 수 없습니다.");
    }

    @Transactional(readOnly = true)
    public ObjectNode detail(long id) {
        if (id < 1) throw Problem.invalid();
        AnalysisJob analysisJob = jobs.findById(id).orElseThrow(this::notFound);
        if (!analysisJob.status.equals("succeeded")) throw notReady();
        try {
            JsonNode spec = Json.parse(analysisJob.specification);
            JsonNode bdd = Json.parse(analysisJob.bdd);
            JsonNode basis = Json.parse(analysisJob.basis);
            JsonNode signature =
                Json.parse(groups.findById(analysisJob.groupId).orElseThrow(this::corrupt).signature);
            for (JsonNode doc : List.of(spec, bdd)) {
                if (!Json.sha(Json.text(doc, "content")).equals(Json.text(doc, "sha256")))
                    throw corrupt();
            }
            if (!spec.path("sha256").equals(basis.path("specification_sha256"))
                || !bdd.path("sha256").equals(basis.path("bdd_sha256"))) throw corrupt();
            ObjectNode result = analysisJob.testEvidence != null ? TestEvidencePolicy.result(Json.parse(analysisJob.result),
                spec.path("content").asText(), bdd.path("content").asText(), Json.parse(analysisJob.testEvidence))
                : AnalysisPolicy.result(
                Json.parse(analysisJob.result),
                spec.path("content").asText(),
                bdd.path("content").asText());
            ArrayNode candidates = Json.array();
            int num = 1;
            for (JsonNode candidate : result.path("candidates"))
                candidates.add(
                    Json.obj(
                        "candidate_no",
                        num++,
                        "revision",
                        0,
                        "status",
                        "pending",
                        "original",
                        candidate,
                        "current",
                        candidate,
                        "history",
                        Json.array()));
            for (CandidateReviewEntity record :
                history.findByJobIdOrderByCandidateNoAscRevisionAsc(id)) {
                if (record.candidateNo < 1 || record.candidateNo > candidates.size())
                    throw corrupt();
                ObjectNode candidateReview = (ObjectNode) candidates.get(record.candidateNo - 1);
                JsonNode candidate = Json.parse(record.candidate);
                String status = status(record.action);
                ObjectNode command =
                    Json.obj(
                        "action",
                        record.action,
                        "reviewer",
                        record.reviewer,
                        "reason",
                        record.reason,
                        "expected_revision",
                        candidateReview.path("revision"));
                if (record.action.equals("edit")) command.set("candidate", candidate);
                validateCommand(command);
                if (record.revision != candidateReview.path("revision").asInt() + 1
                    || record.action.equals("edit") && candidate.equals(candidateReview.get("current"))
                    || !record.action.equals("edit")
                    && (!candidate.equals(candidateReview.get("current"))
                    || candidateReview.path("status").asText().equals(status)))
                    throw corrupt();
                AnalysisPolicy.candidate(
                    candidate, spec.path("content").asText(), bdd.path("content").asText());
                ((ArrayNode) candidateReview.get("history"))
                    .add(
                        Json.obj(
                            "revision",
                            record.revision,
                            "action",
                            record.action,
                            "reviewer",
                            record.reviewer,
                            "reason",
                            record.reason,
                            "created_at",
                            record.createdAt.toString(),
                            "candidate",
                            candidate));
                candidateReview.put("revision", record.revision).put("status", status).set("current", candidate);
            }
            ObjectNode detail =
                Json.obj(
                    "job_id",
                    id,
                    "service",
                    signature.path("service"),
                    "environment",
                    signature.path("environment"),
                    "method",
                    signature.path("method"),
                    "route",
                    signature.path("route"),
                    "deploy_sha",
                    basis.path("deploy_sha"),
                    "finished_at",
                    analysisJob.finishedAt == null ? null : analysisJob.finishedAt.toString(),
                    "summary",
                    result.path("summary"),
                    "existing_bdd_assessment",
                    result.path("existing_bdd_assessment"),
                    "questions",
                    result.path("questions"),
                    "specification",
                    spec.path("content"),
                    "bdd",
                    bdd.path("content"),
                    "candidates",
                    candidates);
            if (analysisJob.testEvidence != null) {
                detail.set("test_evidence", TestEvidencePolicy.input(Json.parse(analysisJob.testEvidence)));
                detail.set("test_assessments", result.path("test_assessments"));
            }
            for (GitHubIssueDelivery delivery : issues.forJob(id)) {
                if (delivery.candidateNo >= 1 && delivery.candidateNo <= candidates.size())
                    ((ObjectNode) candidates.get(delivery.candidateNo - 1)).set("github_issue", GitHubIssueService.summary(delivery));
            }
            JsonNode context = Json.parse(analysisJob.businessContext);
            if (!context.isEmpty()) detail.set("business_context", context);
            return detail;
        } catch (Problem exception) {
            throw corrupt();
        } catch (RuntimeException exception) {
            throw corrupt();
        }
    }

    public static String status(String action) {
        return switch (action) {
            case "approve" -> "approved";
            case "edit" -> "pending";
            case "reject" -> "rejected";
            default -> throw invalid();
        };
    }

    public static ObjectNode validateCommand(JsonNode raw) {
        try {
            Json.fields(
                raw,
                Set.of("action", "reviewer", "reason", "expected_revision", "candidate"),
                Set.of("action", "reviewer", "expected_revision"));
            ObjectNode reviewCommand = raw.deepCopy();
            String action = Json.text(reviewCommand, "action");
            status(action);
            String reviewer = Json.text(reviewCommand, "reviewer").strip();
            if (reviewer.isBlank() || Json.length(reviewer) > 80) throw invalid();
            reviewCommand.put("reviewer", reviewer);
            if (!reviewCommand.has("reason")) reviewCommand.put("reason", "");
            if (!reviewCommand.get("reason").isTextual()) throw invalid();
            String reason = reviewCommand.get("reason").asText().strip();
            if (Json.length(reason) > 2000 || !action.equals("approve") && reason.isBlank())
                throw invalid();
            reviewCommand.put("reason", reason);
            Json.integer(reviewCommand, "expected_revision", 0, Long.MAX_VALUE);
            if (action.equals("edit")) {
                if (!reviewCommand.hasNonNull("candidate")
                    || Json.length(Json.encode(reviewCommand.get("candidate"))) > 60000) throw invalid();
                AnalysisPolicy.candidate(reviewCommand.get("candidate"), null, null);
            } else if (reviewCommand.hasNonNull("candidate")) throw invalid();
            return reviewCommand;
        } catch (Problem exception) {
            throw invalid();
        }
    }

    public ObjectNode submit(long job, int number, JsonNode raw) {
        return submit(job, number, raw, false);
    }

    public ObjectNode submitFromSlack(long job, int number, JsonNode raw) {
        return submit(job, number, raw, true);
    }

    private ObjectNode submit(long job, int number, JsonNode raw, boolean fromSlack) {
        ObjectNode command = validateCommand(raw);
        return transactions.required(
            () -> {
                if (jobs.findLockedById(job).isEmpty()) throw notFound();
                ObjectNode reviewDetail = detail(job);
                if (number < 1 || number > reviewDetail.path("candidates").size()) throw notFound();
                ObjectNode candidateReview = reviewDetail.path("candidates").get(number - 1).deepCopy();
                if (command.path("expected_revision").asLong() != candidateReview.path("revision").asLong())
                    throw conflict();
                String action = command.path("action").asText();
                JsonNode candidate =
                    action.equals("edit") ? command.get("candidate") : candidateReview.get("current");
                if (action.equals("edit")) {
                    if (candidate.equals(candidateReview.get("current"))) throw invalid();
                    try {
                        AnalysisPolicy.candidate(
                            candidate,
                            reviewDetail.path("specification").asText(),
                            reviewDetail.path("bdd").asText());
                    } catch (Problem exception) {
                        throw invalid();
                    }
                } else if (candidateReview.path("status").asText().equals(status(action))) throw conflict();
                int revision = candidateReview.path("revision").asInt() + 1;
                CandidateReviewEntity record = new CandidateReviewEntity();
                record.jobId = job;
                record.candidateNo = number;
                record.revision = revision;
                record.action = action;
                record.reviewer = command.path("reviewer").asText();
                record.reason = command.path("reason").asText();
                record.candidate = Json.encode(candidate);
                history.saveAndFlush(record);
                issues.reviewChanged(job, number, revision, action, fromSlack, reviewDetail, candidate);
                ((ArrayNode) candidateReview.get("history"))
                    .add(
                        Json.obj(
                            "revision",
                            revision,
                            "action",
                            action,
                            "reviewer",
                            command.get("reviewer"),
                            "reason",
                            command.get("reason"),
                            "created_at",
                            record.createdAt.toString(),
                            "candidate",
                            candidate));
                candidateReview.put("revision", revision)
                    .put("status", status(action))
                    .set("current", candidate);
                return candidateReview;
            });
    }

    @Transactional(readOnly = true)
    public ObjectNode list(int limit, Long before) {
        if (limit < 1 || limit > 100 || before != null && before < 1) throw Problem.invalid();
        List<AnalysisJob> rows = jobs.findReviewJobs(before, PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        ArrayNode items = Json.array();
        for (AnalysisJob row : rows.subList(0, Math.min(limit, rows.size()))) {
            ObjectNode reviewDetail = detail(row.id);
            int pending = 0, approved = 0, rejected = 0;
            for (JsonNode candidate : reviewDetail.path("candidates"))
                switch (candidate.path("status").asText()) {
                    case "pending" -> pending++;
                    case "approved" -> approved++;
                    case "rejected" -> rejected++;
                }
            ObjectNode jobSummary = Json.obj();
            for (String fieldName :
                List.of("job_id", "service", "environment", "method", "route", "finished_at"))
                jobSummary.set(fieldName, reviewDetail.get(fieldName));
            jobSummary.put("candidate_count", reviewDetail.path("candidates").size())
                .put("pending_count", pending)
                .put("approved_count", approved)
                .put("rejected_count", rejected)
                .put("question_count", reviewDetail.path("questions").size());
            items.add(jobSummary);
        }
        return Json.obj(
            "items",
            items,
            "next_before_id",
            more ? items.get(items.size() - 1).get("job_id") : null);
    }

    @Transactional(readOnly = true)
    public String approved(long job) {
        ObjectNode reviewDetail = detail(job);
        StringBuilder out =
            new StringBuilder(
                "# 개발자가 승인한 BDD 후보입니다. 단계 코드 구현과 테스트 실행은 별도로 필요합니다.\n# 분석 작업: "
                    + job
                    + "\nFeature: 개발자가 검토한 BDD 시나리오\n");
        boolean any = false;
        for (JsonNode candidate : reviewDetail.path("candidates"))
            if (candidate.path("status").asText().equals("approved")) {
                any = true;
                out.append("\n  # 후보 ")
                    .append(candidate.path("candidate_no").asInt())
                    .append(", 검토 revision ")
                    .append(candidate.path("revision").asInt());
                AnalysisPolicy.scenario(out, candidate.get("current"));
            }
        if (!any) throw notReady();
        return out.toString();
    }
}
