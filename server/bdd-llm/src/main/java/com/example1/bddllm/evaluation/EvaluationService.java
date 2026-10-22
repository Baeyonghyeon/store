package com.example1.bddllm.evaluation;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.analysis.PromptCatalog;
import com.example1.bddllm.analysis.LlmGateway;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.evidence.EvidenceRegistry;
import com.example1.bddllm.job.JobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class EvaluationService {
    private final LlmGateway llm;
    private final JobService jobs;

    public EvaluationService(LlmGateway llm, JobService jobs) {
        this.llm = llm;
        this.jobs = jobs;
    }

    public record Case(
        String id,
        ErrorEvent event,
        ObjectNode spec,
        ObjectNode bdd,
        ObjectNode expected,
        ObjectNode reference) {
    }

    public List<Case> load(String suite) throws Exception {
        Path root = Path.of(suite).toRealPath();
        List<Case> cases = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path folder :
                dirs.filter(Files::isDirectory)
                    .filter(directory -> !directory.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList()) {
                if (!folder.toRealPath().startsWith(root))
                    throw new IllegalArgumentException("invalid_evaluation_path");
                for (String requiredFile :
                    List.of(
                        "event.json",
                        "expected.json",
                        "reference.json",
                        "specification.md",
                        "existing.feature"))
                    if (!folder.resolve(requiredFile).toRealPath().startsWith(folder.toRealPath()))
                        throw new IllegalArgumentException("invalid_evaluation_path");
                ErrorEvent errorEvent =
                    ErrorEvent.read(Json.parse(Files.readString(folder.resolve("event.json"))));
                ObjectNode spec = EvidenceRegistry.document(folder, "specification.md");
                ObjectNode bdd = EvidenceRegistry.document(folder, "existing.feature");
                ObjectNode exp =
                    (ObjectNode) Json.parse(Files.readString(folder.resolve("expected.json")));
                ObjectNode ref =
                    AnalysisPolicy.result(
                        Json.parse(Files.readString(folder.resolve("reference.json"))),
                        spec.path("content").asText(),
                        bdd.path("content").asText());
                String id = folder.getFileName().toString();
                if (!id.equals(exp.path("case_id").asText())
                    || !id.matches("[a-z][a-z0-9_]{0,63}")
                    || !Set.of(
                        "missing_scenario",
                        "already_covered",
                        "weak_assertion",
                        "undefined_policy")
                    .contains(exp.path("category").asText()))
                    throw new IllegalArgumentException("invalid_evaluation_case");
                for (JsonNode rule : exp.path("assertions")) {
                    if (!spec.path("content")
                        .asText()
                        .contains(Json.text(rule, "specification_quote")))
                        throw new IllegalArgumentException("invalid_rubric_quote");
                    for (String patternFieldName : List.of("patterns", "forbidden_patterns"))
                        for (JsonNode pat : rule.path(patternFieldName)) Pattern.compile(pat.asText());
                }
                for (JsonNode pat : exp.path("question_patterns")) Pattern.compile(pat.asText());
                EvaluationService.Case evaluationCase = new Case(id, errorEvent, spec, bdd, exp, ref);
                if (!score(evaluationCase, ref).path("automated_pass").asBoolean())
                    throw new IllegalArgumentException("reference_does_not_pass_rubric");
                cases.add(evaluationCase);
            }
        }
        if (cases.isEmpty()) throw new IllegalArgumentException("empty_evaluation_suite");
        return cases;
    }

    public ObjectNode run(String suite, String output, boolean reference) throws Exception {
        List<EvaluationService.Case> cases = load(suite);
        ArrayNode reports = Json.array();
        PromptCatalog.Template promptTemplate = jobs.promptTemplate(false);
        String instructions = promptTemplate.content();
        for (EvaluationService.Case evaluationCase : cases) {
            ObjectNode result;
            boolean cacheHit = false;
            String key = null;
            if (reference) result = evaluationCase.reference;
            else {
                ObjectNode data =
                    Json.obj(
                        "version",
                        "1",
                        "prompt_file",
                        promptTemplate.fileName(),
                        "model",
                        jobs.model(),
                        "instructions",
                        instructions,
                        "evidence",
                        AnalysisPolicy.evidence(evaluationCase.event, evaluationCase.spec, evaluationCase.bdd),
                        "schema",
                        AnalysisPolicy.SCHEMA,
                        "max_output_tokens",
                        6000);
                if (!jobs.provider().equals("openai")) data.put("provider", jobs.provider());
                key = Json.sha(Json.spaced(data));
                Path folder = Path.of(output).toAbsolutePath().resolve(key);
                Files.createDirectories(folder);
                try (FileChannel channel =
                         FileChannel.open(
                             folder.resolve(".lock"),
                             StandardOpenOption.CREATE,
                             StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) throw new IllegalStateException("evaluation_busy");
                    Path path = folder.resolve("analysis.json");
                    cacheHit = Files.exists(path);
                    if (cacheHit)
                        result =
                            AnalysisPolicy.result(
                                Json.parse(Files.readString(path)),
                                evaluationCase.spec.path("content").asText(),
                                evaluationCase.bdd.path("content").asText());
                    else {
                        AnalysisPolicy.basis(
                            evaluationCase.event,
                            evaluationCase.spec,
                            evaluationCase.bdd,
                            instructions,
                            jobs.provider(),
                            jobs.model());
                        result =
                            llm.generate(
                                jobs.provider(),
                                jobs.model(),
                                instructions,
                                AnalysisPolicy.evidence(evaluationCase.event, evaluationCase.spec, evaluationCase.bdd));
                        AnalysisPolicy.result(
                            result,
                            evaluationCase.spec.path("content").asText(),
                            evaluationCase.bdd.path("content").asText());
                        atomic(path, Json.pretty(result) + "\n");
                    }
                    atomic(folder.resolve("candidates.feature"), AnalysisPolicy.feature(result));
                }
            }
            ObjectNode report = score(evaluationCase, result);
            report.put("cache_hit", cacheHit);
            if (key != null) report.put("analysis_key", key);
            report.put(
                    "input_hash",
                    Json.sha(
                        Json.canonical(
                            Json.obj(
                                "event",
                                evaluationCase.event.payload(),
                                "specification",
                                evaluationCase.spec.path("content"),
                                "bdd",
                                evaluationCase.bdd.path("content")))))
                .put("rubric_hash", Json.sha(Json.canonical(evaluationCase.expected)));
            reports.add(report);
        }
        boolean pass = true;
        for (JsonNode caseReport : reports) pass &= caseReport.path("automated_pass").asBoolean();
        ObjectNode report =
            Json.obj(
                "evaluator_version",
                "1",
                "mode",
                reference ? "reference" : "llm",
                "automated_pass",
                pass,
                "review_required",
                true,
                "cases",
                reports);
        Files.createDirectories(Path.of(output));
        atomic(
            Path.of(output).resolve(reference ? "reference-report.json" : "report.json"),
            Json.pretty(report) + "\n");
        return report;
    }

    private static void atomic(Path path, String content) throws Exception {
        Path tmp = Files.createTempFile(path.toAbsolutePath().getParent(), ".bdd-", ".tmp");
        try {
            Files.writeString(tmp, content);
            Files.move(
                tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void check(ArrayNode checks, String name, boolean passed, String detail) {
        checks.add(Json.obj("name", name, "passed", passed, "detail", detail));
    }

    private static boolean matches(JsonNode patterns, String text) {
        for (JsonNode pattern : patterns)
            if (!Pattern.compile(pattern.asText(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(text)
                .find()) return false;
        return true;
    }

    public static ObjectNode score(Case evaluationCase, JsonNode result) {
        ObjectNode expected = evaluationCase.expected;
        JsonNode candidates = result.path("candidates");
        Set<String> types = new HashSet<>();
        for (JsonNode candidate : candidates) types.add(candidate.path("gap_type").asText());
        String observed =
            candidates.isEmpty()
                ? (result.path("questions").isEmpty()
                ? "already_covered"
                : "undefined_policy")
                : types.size() == 1 ? types.iterator().next() : "mixed";
        String category = expected.path("category").asText();
        boolean positive = Set.of("missing_scenario", "weak_assertion").contains(category);
        ArrayNode checks = Json.array();
        check(
            checks,
            "category",
            observed.equals(category),
            "기대: " + category + ", 관측: " + observed);
        check(checks, "candidate_count", candidates.size() == (positive ? 1 : 0), "후보 개수 확인");
        check(
            checks,
            "questions",
            !result.path("questions").isEmpty() == category.equals("undefined_policy"),
            "정책 질문 확인");
        try {
            AnalysisPolicy.result(
                result, evaluationCase.spec.path("content").asText(), evaluationCase.bdd.path("content").asText());
            check(checks, "evidence", true, "인용 원문 일치. 의미는 사람의 검토가 필요합니다.");
        } catch (Problem exception) {
            check(checks, "evidence", false, "원문에 없는 인용");
        }
        if (category.equals("weak_assertion")) {
            boolean evidence = !candidates.isEmpty();
            for (JsonNode cand : candidates)
                evidence &= !cand.path("existing_bdd_quotes").isEmpty();
            check(checks, "existing_bdd_evidence", evidence, "보강할 기존 BDD 인용");
        }
        for (JsonNode rule : expected.path("assertions")) {
            boolean found = false;
            for (JsonNode cand : candidates)
                for (JsonNode outcome : cand.path("then")) {
                    boolean forbidden = false;
                    for (JsonNode forbiddenPattern : rule.path("forbidden_patterns"))
                        forbidden |=
                            Pattern.compile(
                                    forbiddenPattern.asText(),
                                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                                .matcher(outcome.path("behavior").asText())
                                .find();
                    found |=
                        outcome.path("specification_quote")
                            .asText()
                            .contains(rule.path("specification_quote").asText())
                            && matches(
                            rule.path("patterns"),
                            outcome.path("behavior").asText())
                            && !forbidden;
                }
            check(
                checks,
                "assertion:" + rule.path("id").asText(),
                found,
                rule.path("description").asText());
        }
        if (!expected.path("question_patterns").isEmpty()) {
            boolean found = false;
            for (JsonNode question : result.path("questions"))
                found |= matches(expected.path("question_patterns"), question.asText());
            check(checks, "policy_question", found, "정책을 임의로 정하지 않고 질문");
        }
        boolean pass = true;
        for (JsonNode check : checks) pass &= check.path("passed").asBoolean();
        return Json.obj(
            "case_id",
            evaluationCase.id,
            "category",
            category,
            "observed_category",
            observed,
            "automated_pass",
            pass,
            "checks",
            checks,
            "review_required",
            true,
            "review_notes",
            expected.path("review_notes"));
    }
}
