package com.example1.bddllm.github;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public final class GitHubIssueBody {
    private GitHubIssueBody() {}
    public static String marker(UUID marker) { return "<!-- bdd-gap-issue:" + marker + " -->"; }
    public static String title(JsonNode candidate) {
        String title = "[BDD] " + candidate.path("title").asText().replaceAll("[\\r\\n]+", " ");
        return title.codePointCount(0, title.length()) > 240 ? title.substring(0, title.offsetByCodePoints(0, 240)) : title;
    }
    public static String body(JsonNode detail, JsonNode candidate, int number, int revision, UUID marker) {
        StringBuilder body = new StringBuilder("## 보완할 테스트 시나리오\n\n");
        body.append(candidate.path("reason").asText()).append("\n\n## 관측된 오류와 업무 조건\n\n")
            .append(detail.path("summary").asText()).append("\n\n- 서비스: ").append(detail.path("service").asText())
            .append("\n- API: `").append(detail.path("method").asText()).append(' ').append(detail.path("route").asText())
            .append("`\n- 배포 SHA: `").append(detail.path("deploy_sha").asText()).append("`\n");
        if (detail.has("business_context")) body.append("\n```json\n").append(Json.pretty(detail.path("business_context"))).append("\n```\n");
        body.append("\n## 승인된 BDD 시나리오\n\n```gherkin\nFeature: 운영 오류에서 발견한 시나리오 공백\n");
        AnalysisPolicy.scenario(body, candidate);
        body.append("\n```\n\n## 명세 근거\n\n");
        for (JsonNode expected : candidate.path("then"))
            body.append("- ").append(expected.path("behavior").asText()).append("\n\n> ")
                .append(expected.path("specification_quote").asText().replace("\n", "\n> ")).append("\n\n");
        body.append("## 기존 BDD와의 비교\n\n").append(detail.path("existing_bdd_assessment").asText()).append("\n\n");
        for (JsonNode quote : candidate.path("existing_bdd_quotes")) body.append("> ").append(quote.asText().replace("\n", "\n> ")).append("\n\n");
        if (detail.has("test_evidence")) {
            body.append("## 관련 테스트 근거\n\n");
            JsonNode evidence = detail.path("test_evidence");
            body.append("저장소: `").append(evidence.path("repository").asText()).append("` · 커밋: `")
                .append(evidence.path("commit_sha").asText()).append("`\n\n");
            for (JsonNode assessment : detail.path("test_assessments")) {
                if (assessment.path("candidate_no").asInt() != number) continue;
                body.append(assessment.path("explanation").asText()).append("\n\n");
                for (JsonNode citation : assessment.path("citations")) {
                    for (JsonNode chunk : evidence.path("chunks")) {
                        if (!chunk.path("id").equals(citation.path("chunk_id"))) continue;
                        body.append("- `").append(chunk.path("path").asText()).append(":")
                            .append(chunk.path("start_line").asInt()).append("–").append(chunk.path("end_line").asInt()).append("`\n");
                    }
                }
            }
        }
        body.append("\n## 완료 기준\n\n- [ ] 승인 시나리오에 대응하는 테스트 구현\n- [ ] 테스트 실행 결과 확인\n\n")
            .append("이 Issue는 개발자가 검토한 구현 과제입니다. 테스트 실행·통과나 원인 확정을 뜻하지 않습니다.\n\n")
            .append("분석 작업 #").append(detail.path("job_id").asLong()).append(" · 후보 ").append(number).append(" · 승인 수정본 ").append(revision)
            .append("\n\n").append(marker(marker)).append("\n<!-- bdd-review:").append(revision).append(" -->\n");
        return body.toString();
    }
}
