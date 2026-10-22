package com.example1.bddllm.slack;

import com.example1.bddllm.analysis.AnalysisPolicy;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.review.ReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SlackViews {
    public static ObjectNode plain(String text) {
        return Json.obj("type", "plain_text", "text", text, "emoji", false);
    }

    public static ObjectNode section(String text) {
        return Json.obj("type", "section", "text", plain(text));
    }

    public static ObjectNode notice(String title, String message) {
        return Json.obj(
            "type",
            "modal",
            "title",
            plain(title),
            "close",
            plain("닫기"),
            "blocks",
            List.of(section(message)));
    }

    private static String clip(String text, int max) {
        return Json.length(text) <= max
            ? text
            : text.substring(0, text.offsetByCodePoints(0, max))
            + "\n… 일부 생략. 전체 내용은 로컬 검토 화면에서 확인하세요.";
    }

    public static ObjectNode message(JsonNode reviewDetail, JsonNode candidate, String metadata) {
        ArrayNode blocks = Json.array();
        blocks.add(
            Json.obj("type", "header", "text", plain("BDD 분석 #" + reviewDetail.path("job_id").asLong())));
        blocks.add(
            section(
                reviewDetail.path("service").asText()
                    + " · "
                    + reviewDetail.path("method").asText()
                    + " "
                    + reviewDetail.path("route").asText()));
        blocks.add(section(clip("분석 요약\n" + reviewDetail.path("summary").asText(), 2800)));
        if (reviewDetail.has("test_evidence")) {
            JsonNode evidence = reviewDetail.path("test_evidence");
            blocks.add(section("테스트 근거 · " + evidence.path("repository").asText() + " @ "
                + evidence.path("commit_sha").asText() + "\n선택 " + evidence.path("selected_tests").size()
                + " / 발견 " + evidence.path("discovered_test_count").asInt()
                + " · 제외 " + evidence.path("excluded_tests").size() + "\n" + evidence.path("scope_note").asText()));
            if (reviewDetail.hasNonNull("evidence_url")) blocks.add(Json.obj("type", "actions", "elements", List.of(
                Json.obj("type", "button", "text", plain("테스트 근거 상세"), "action_id", "bdd_source_link", "url", reviewDetail.path("evidence_url")))));
        }
        blocks.add(Json.obj("type", "divider"));
        if (candidate != null) {
            blocks.add(
                section(
                    "후보 "
                        + candidate.path("candidate_no").asInt()
                        + " · 수정본 "
                        + candidate.path("revision").asInt()
                        + " · "
                        + candidate.path("status").asText()));
            JsonNode current = candidate.path("current");
            blocks.add(section(clip(current.path("title").asText(), 2800)));
            blocks.add(bulletList("Given · 사전 조건", current.path("given"), null));
            blocks.add(section(clip("When · 행동\n" + current.path("when").asText(), 2800)));
            blocks.add(bulletList("Then · 기대 동작", current.path("then"), "behavior"));
            blocks.add(section(clip("제안 이유\n" + current.path("reason").asText(), 2800)));
            for (JsonNode assessment : reviewDetail.path("test_assessments"))
                if (assessment.path("candidate_no").asInt() == candidate.path("candidate_no").asInt())
                    blocks.add(section(clip("실제 테스트 검토 · " + assessment.path("status").asText()
                        + "\n" + assessment.path("explanation").asText(), 2800)));
            if (candidate.has("github_issue")) {
                JsonNode issue = candidate.path("github_issue");
                String state = issue.path("state").asText();
                String label = switch (state) {
                    case "published" -> "등록 완료";
                    case "pending" -> "등록 대기";
                    case "processing" -> "등록 확인 중";
                    case "uncertain" -> "전송 결과 확인 필요";
                    case "cancelled" -> "최신 승인 대기";
                    default -> "등록 실패";
                };
                blocks.add(section("GitHub Issue · " + label + "\n" + issue.path("repository").asText()
                    + (issue.hasNonNull("error_code") ? "\n" + issue.path("error_code").asText() : "")));
                if (issue.hasNonNull("url")) blocks.add(Json.obj("type", "actions", "elements", List.of(
                    Json.obj("type", "button", "text", plain("GitHub Issue #" + issue.path("number").asLong()),
                        "action_id", "bdd_github_issue_link", "url", issue.path("url")))));
            }
            ArrayNode buttons = Json.array();
            for (String action : List.of("approve", "edit", "reject", "evidence"))
                buttons.add(
                    Json.obj(
                        "type",
                        "button",
                        "text",
                        plain(label(action)),
                        "action_id",
                        "bdd_" + action,
                        "value",
                        metadata));
            blocks.add(Json.obj("type", "actions", "elements", buttons));
        } else {
            blocks.add(
                section(
                    clip(
                        "기존 BDD 검토\n" + reviewDetail.path("existing_bdd_assessment").asText(),
                        2800)));
            blocks.add(bulletList("확인이 필요한 정책", reviewDetail.path("questions"), null));
        }
        return Json.obj(
            "text",
            "BDD 분석 완료: 분석 #" + reviewDetail.path("job_id").asLong(),
            "blocks",
            blocks,
            "unfurl_links",
            false,
            "unfurl_media",
            false);
    }

    /**
     * Native list items preserve line breaks without interpreting model text as markup.
     */
    private static ObjectNode bulletList(String title, JsonNode values, String itemField) {
        ArrayNode elements = Json.array();
        elements.add(
            Json.obj(
                "type",
                "rich_text_section",
                "elements",
                List.of(
                    Json.obj(
                        "type",
                        "text",
                        "text",
                        title + "\n",
                        "style",
                        Json.obj("bold", true)))));
        ArrayNode items = Json.array();
        int remaining = 2800 - Json.length(title) - 1;
        boolean omitted = false;
        for (JsonNode value : values) {
            if (remaining <= 0) {
                omitted = true;
                break;
            }
            String text = itemField == null ? value.asText() : value.path(itemField).asText();
            int shown = Math.min(Json.length(text), remaining);
            String visible = text.substring(0, text.offsetByCodePoints(0, shown));
            items.add(
                Json.obj(
                    "type",
                    "rich_text_section",
                    "elements",
                    List.of(Json.obj("type", "text", "text", visible))));
            remaining -= shown + 2;
            if (shown < Json.length(text)) {
                omitted = true;
                break;
            }
        }
        if (!items.isEmpty())
            elements.add(Json.obj("type", "rich_text_list", "style", "bullet", "elements", items));
        if (omitted)
            elements.add(
                Json.obj(
                    "type",
                    "rich_text_section",
                    "elements",
                    List.of(
                        Json.obj(
                            "type",
                            "text",
                            "text",
                            "\n… 일부 생략. 전체 내용은 로컬 검토 화면에서 확인하세요."))));
        return Json.obj("type", "rich_text", "elements", elements);
    }

    public static String label(String action) {
        return switch (action) {
            case "approve" -> "승인";
            case "edit" -> "수정";
            case "reject" -> "반려";
            default -> "근거 보기";
        };
    }

    public static ObjectNode modal(JsonNode reviewDetail, JsonNode candidate, String action, String metadata) {
        if (action.equals("evidence")) {
            ObjectNode view = notice("분석에 사용한 근거", "분석 당시 저장된 명세와 BDD입니다.");
            ArrayNode blocks = (ArrayNode) view.get("blocks");
            for (String field : List.of("specification", "bdd")) {
                blocks.add(Json.obj("type", "divider"));
                String text = reviewDetail.path(field).asText();
                int total = Json.length(text), shown = Math.min(total, 2800 * 12);
                for (int start = 0; start < shown; start += 2800)
                    blocks.add(
                        section(
                            text.substring(
                                text.offsetByCodePoints(0, start),
                                text.offsetByCodePoints(
                                    0, Math.min(start + 2800, shown)))));
                if (total > shown) blocks.add(section("일부 생략. 전체 근거는 로컬 검토 화면에서 확인하세요."));
            }
            if (reviewDetail.has("test_evidence")) {
                JsonNode tests = reviewDetail.path("test_evidence");
                blocks.add(section(tests.path("scope_note").asText()));
                blocks.add(section("선택 " + tests.path("selected_tests").size() + " / 발견 " + tests.path("discovered_test_count").asInt()
                    + " · 제외 " + tests.path("excluded_tests").size()));
                int shown = 0;
                for (JsonNode assessment : reviewDetail.path("test_assessments")) {
                    if (++shown > 5) break;
                    blocks.add(section(clip(assessment.path("condition").asText() + " · " + assessment.path("status").asText()
                        + "\n" + assessment.path("explanation").asText(), 2800)));
                }
                shown = 0;
                List<JsonNode> sourceChunks = new ArrayList<>();
                tests.path("chunks").forEach(sourceChunks::add);
                sourceChunks.sort(java.util.Comparator.comparingInt(chunk -> switch (chunk.path("kind").asText()) {
                    case "test" -> 0;
                    case "setup_or_helper" -> 1;
                    default -> 2;
                }));
                for (JsonNode chunk : sourceChunks) {
                    if (++shown > 8) break;
                    blocks.add(section(clip(chunk.path("path").asText() + ":" + chunk.path("start_line").asInt()
                        + "–" + chunk.path("end_line").asInt() + "\n" + chunk.path("content").asText(), 2800)));
                }
                blocks.add(section("전체 코드와 제외 사유: /jobs/" + reviewDetail.path("job_id").asLong() + "/evidence 또는 로컬 검토 화면"));
            }
            return view;
        }
        ObjectNode view = notice("BDD 후보 " + label(action), "수정한 후보는 검토 대기가 됩니다. 연결된 서비스는 Slack 승인 후 GitHub Issue로 등록됩니다.");
        view.put("callback_id", "bdd_review_submit").put("private_metadata", metadata);
        ArrayNode blocks = (ArrayNode) view.get("blocks");
        JsonNode current = candidate.path("current");
        if (action.equals("edit")) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("title", current.path("title").asText());
            values.put("given", join(current.path("given"), null));
            values.put("when", current.path("when").asText());
            values.put("then_behaviors", join(current.path("then"), "behavior"));
            values.put("then_quotes", join(current.path("then"), "specification_quote"));
            // Never silently truncate or split a multiline item into different assertions.
            for (Map.Entry<String, String> value : values.entrySet())
                if (Json.length(value.getValue()) > 3000)
                    return notice("Slack 편집 한도 초과", "원문을 자르지 않았습니다. 로컬 검토 화면(/reviews)에서 수정해 주세요.");
            for (JsonNode precondition : current.path("given"))
                if (precondition.asText().contains("\n") || precondition.asText().contains("\r"))
                    return notice("항목에 줄바꿈 포함", "원문 항목 구성을 보존하려면 로컬 검토 화면에서 수정하세요.");
            for (JsonNode expectedBehavior : current.path("then"))
                for (String key : List.of("behavior", "specification_quote"))
                    if (expectedBehavior.path(key).asText().contains("\n") || expectedBehavior.path(key).asText().contains("\r"))
                        return notice("항목에 줄바꿈 포함", "로컬 검토 화면에서 수정하세요.");
            for (Map.Entry<String, String> inputEntry : values.entrySet())
                blocks.add(input(inputEntry.getKey(), inputEntry.getValue(), false));
        } else {
            StringBuilder scenario = new StringBuilder();
            AnalysisPolicy.scenario(scenario, current);
            if (Json.length(scenario.toString()) > 2800 * 20)
                return notice("Slack 표시 한도 초과", "전체 후보를 확인할 수 있도록 로컬 검토 화면에서 검토하세요.");
            String scenarioText = scenario.toString();
            for (int start = 0; start < scenarioText.length(); ) {
                int end =
                    scenarioText.offsetByCodePoints(
                        start, Math.min(2800, scenarioText.codePointCount(start, scenarioText.length())));
                blocks.add(section(scenarioText.substring(start, end)));
                start = end;
            }
        }
        blocks.add(input("reason", "", action.equals("approve")));
        view.set("submit", plain(action.equals("edit") ? "수정 저장" : label(action)));
        return view;
    }

    private static String join(JsonNode array, String key) {
        List<String> lines = new ArrayList<>();
        for (JsonNode value : array) lines.add(key == null ? value.asText() : value.path(key).asText());
        return String.join("\n", lines);
    }

    public static ObjectNode input(String name, String initial, boolean optional) {
        Map<String, String> labels =
            Map.of(
                "title",
                "시나리오 제목",
                "given",
                "Given · 사전 조건 (한 줄에 하나)",
                "when",
                "When · 행동",
                "then_behaviors",
                "Then · 기대 동작 (한 줄에 하나)",
                "then_quotes",
                "Then · 같은 순서의 명세 원문 인용",
                "reason",
                "검토 의견");
        ObjectNode element =
            Json.obj(
                "type",
                "plain_text_input",
                "action_id",
                "value",
                "multiline",
                !name.equals("title"),
                "max_length",
                name.equals("reason") ? 2000 : 3000);
        if (!initial.isEmpty()) element.put("initial_value", initial);
        return Json.obj(
            "type",
            "input",
            "block_id",
            name,
            "label",
            plain(labels.get(name)),
            "optional",
            optional,
            "element",
            element);
    }

    public static ObjectNode submission(
        JsonNode view, JsonNode current, String action, String reviewer, long revision) {
        ObjectNode cmd =
            Json.obj(
                "action",
                action,
                "reviewer",
                reviewer,
                "reason",
                value(view, "reason", action.equals("approve")),
                "expected_revision",
                revision);
        if (action.equals("edit")) {
            ObjectNode candidate = current.deepCopy();
            candidate.put("title", value(view, "title", false));
            candidate.put("when", value(view, "when", false));
            candidate.set("given", lines(value(view, "given", false)));
            ArrayNode behaviors = lines(value(view, "then_behaviors", false));
            ArrayNode quotes = lines(value(view, "then_quotes", false));
            if (behaviors.size() != quotes.size()) throw ReviewService.invalid();
            ArrayNode outcomes = Json.array();
            for (int behaviorIndex = 0; behaviorIndex < behaviors.size(); behaviorIndex++)
                outcomes.add(
                    Json.obj(
                        "behavior",
                        behaviors.get(behaviorIndex),
                        "specification_quote",
                        quotes.get(behaviorIndex)));
            candidate.set("then", outcomes);
            cmd.set("candidate", candidate);
        }
        return cmd;
    }

    private static String value(JsonNode view, String name, boolean optional) {
        JsonNode inputValue = view.path("state").path("values").path(name).path("value").path("value");
        if (inputValue.isNull() || inputValue.isMissingNode()) {
            if (optional) return "";
            throw ReviewService.invalid();
        }
        if (!inputValue.isTextual()
            || !optional && inputValue.asText().isBlank()
            || Json.length(inputValue.asText()) > (name.equals("reason") ? 2000 : 3000))
            throw ReviewService.invalid();
        return inputValue.asText().strip();
    }

    private static ArrayNode lines(String text) {
        ArrayNode result = Json.array();
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.isBlank()) throw ReviewService.invalid();
            result.add(line.strip());
        }
        return result;
    }

    public static ObjectNode restore(JsonNode original, String message, boolean conflict) {
        ObjectNode restored = Json.obj();
        for (String fieldName :
            List.of(
                "type",
                "title",
                "close",
                "submit",
                "callback_id",
                "private_metadata",
                "blocks"))
            if (original.has(fieldName)) restored.set(fieldName, original.get(fieldName).deepCopy());
        ArrayNode blocks = (ArrayNode) restored.get("blocks");
        for (JsonNode block : blocks) {
            if (block.path("type").asText().equals("input")) {
                JsonNode entry =
                    original.path("state")
                        .path("values")
                        .path(block.path("block_id").asText())
                        .path("value")
                        .get("value");
                if (entry != null) {
                    ObjectNode element = (ObjectNode) block.get("element");
                    if (entry.isTextual() && !entry.asText().isEmpty())
                        element.put("initial_value", entry.asText());
                    else element.remove("initial_value");
                }
            }
        }
        blocks.insert(0, section(message));
        if (conflict) restored.remove("submit");
        return restored;
    }
}
