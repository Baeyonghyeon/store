package com.example1.bddllm.evidence;

import com.example1.bddllm.analysis.TestEvidencePolicy;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.example1.bddllm.event.Grouping;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TestEvidenceService {
    private final SourceCollectionService sources;

    public TestEvidenceService(SourceCollectionService sources) {
        this.sources = sources;
    }

    public ObjectNode collect(ErrorEvent event, ObjectNode docs, String prompt) {
        JsonNode sourceConfiguration = docs.path("test_source_config");
        TestSourceConfig config = new TestSourceConfig(sourceConfiguration.path("repository").asText(), strings(sourceConfiguration.path("test_files")),
            strings(sourceConfiguration.path("support_files")), strings(sourceConfiguration.path("target_symbols")), strings(sourceConfiguration.path("search_terms")));
        return select(event, docs, prompt, config, sources.collect(config, event.payload().path("deploy_sha").asText()));
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    public static ObjectNode select(ErrorEvent event, ObjectNode docs, String prompt, TestSourceConfig config, ArrayNode documents) {
        JavaTestExtractor.Extraction extraction = new JavaTestExtractor().extract(documents);
        ArrayNode files = Json.array();
        for (JsonNode document : documents) {
            ObjectNode file = document.deepCopy();
            file.remove("content");
            files.add(file);
        }
        ObjectNode snapshot = Json.obj("repository", config.repository(), "commit_sha", event.payload().path("deploy_sha").asText().toLowerCase(Locale.ROOT),
            "retrieval_version", JavaTestExtractor.VERSION, "config", config.json(), "scope_note", TestEvidencePolicy.SCOPE_NOTE,
            "source_files", documents, "searched_files", files, "discovered_test_count", extraction.bundles().size(),
            "selected_tests", Json.array(), "chunks", Json.array(), "citation_options", Json.array(), "citation_options_version", "1", "excluded_tests", Json.array(),
            "warnings", extraction.warnings(), "selection_complete", false,
            "input_budget", Json.obj("unit", "unicode_codepoints_conservative_not_tokens", "limit", TestEvidencePolicy.INPUT_LIMIT, "reserved_output_tokens", 6000));
        List<JsonNode> ranked = new ArrayList<>();
        extraction.bundles().forEach(ranked::add);
        ranked.sort(Comparator.<JsonNode>comparingInt(bundle -> score(bundle, event, config)).reversed()
            .thenComparing(bundle -> bundle.path("path").asText()).thenComparingInt(bundle -> bundle.path("start_line").asInt()));
        ArrayNode selected = (ArrayNode) snapshot.get("selected_tests"), excluded = (ArrayNode) snapshot.get("excluded_tests");
        // Record exclusions first so their metadata is included while selecting whole bundles.
        for (JsonNode bundle : ranked) excluded.add(Json.obj("id", bundle.path("id"), "reason", "input_budget"));
        Map<String, JsonNode> chunks = new LinkedHashMap<>();
        if (size(event, docs, prompt, snapshot) > TestEvidencePolicy.INPUT_LIMIT) throw tooLarge();
        for (JsonNode bundle : ranked) {
            ObjectNode test = bundle.deepCopy();
            test.remove("chunks");
            ArrayNode ids = Json.array();
            Map<String, JsonNode> trial = new LinkedHashMap<>(chunks);
            for (JsonNode chunk : bundle.path("chunks")) {
                ids.add(chunk.path("id"));
                trial.putIfAbsent(chunk.path("id").asText(), chunk);
            }
            test.set("chunk_ids", ids);
            test.put("rank_score", score(bundle, event, config));
            int excludedIndex = 0;
            while (!excluded.get(excludedIndex).path("id").equals(bundle.path("id"))) excludedIndex++;
            JsonNode exclusion = excluded.remove(excludedIndex);
            selected.add(test);
            ArrayNode values = Json.array();
            trial.values().forEach(values::add);
            snapshot.set("chunks", values);
            snapshot.set("citation_options", citationOptions(trial.values()));
            if (size(event, docs, prompt, snapshot) <= TestEvidencePolicy.INPUT_LIMIT) {
                chunks = trial;
            } else {
                selected.remove(selected.size() - 1);
                excluded.insert(excludedIndex, exclusion);
                values = Json.array();
                chunks.values().forEach(values::add);
                snapshot.set("chunks", values);
                snapshot.set("citation_options", citationOptions(chunks.values()));
            }
        }
        snapshot.put("selection_complete", excluded.isEmpty() && extraction.warnings().isEmpty());
        if (size(event, docs, prompt, snapshot) > TestEvidencePolicy.INPUT_LIMIT) throw tooLarge();
        TestEvidencePolicy.validateSnapshot(snapshot);
        return snapshot;
    }

    private static ArrayNode citationOptions(Iterable<JsonNode> chunks) {
        ArrayNode options = Json.array();
        for (JsonNode chunk : chunks) {
            if (!chunk.path("kind").asText().equals("test")) continue;
            List<String> lines = chunk.path("content").asText().lines().map(String::strip)
                .filter(line -> !line.startsWith("//") && Json.length(line) <= 120
                    && (line.contains(".andExpect(") || line.contains("assertThat(")
                    || line.contains("assertEquals(") || line.contains("assertTrue(") || line.contains("assertFalse(")))
                .sorted(Comparator.comparingInt(line -> line.contains(".andExpect(") ? 0 : 1)).limit(2).toList();
            for (String line : lines) options.add(Json.obj("chunk_id", chunk.path("id"), "quote", line));
        }
        return options;
    }

    private static int size(ErrorEvent event, ObjectNode docs, String prompt, JsonNode snapshot) {
        return TestEvidencePolicy.inputSize(prompt, TestEvidencePolicy.evidence(event, docs.path("specification"), docs.path("bdd"), snapshot));
    }

    private static Problem tooLarge() {
        return new Problem(422, "analysis_input_too_large", "코드를 제외한 기본 분석 입력도 한도를 넘습니다.");
    }

    private static int score(JsonNode bundle, ErrorEvent event, TestSourceConfig config) {
        String text = bundle.path("chunks").toString().toLowerCase(Locale.ROOT);
        int score = 0;
        // Retrieval uses group-stable features, not event IDs, timestamps or request IDs.
        if (text.contains(event.route().toLowerCase(Locale.ROOT))) score += 10000;
        Matcher frames = Pattern.compile("(?:[A-Za-z_$][\\w$]*\\.)+([A-Za-z_$][\\w$]*)\\.([A-Za-z_$][\\w$]*)\\(")
            .matcher(Json.encode(Grouping.signature(event).path("stack_frames")));
        while (frames.find()) {
            if (text.contains(frames.group(1).toLowerCase(Locale.ROOT))) score += 1000;
            if (text.contains(frames.group(2).toLowerCase(Locale.ROOT))) score += 100;
        }
        for (String symbol : config.targetSymbols()) if (text.contains(symbol.toLowerCase(Locale.ROOT))) score += 100;
        for (String term : config.searchTerms()) if (text.contains(term.toLowerCase(Locale.ROOT))) score += 10;
        for (JsonNode condition : event.context())
            if (!condition.asText().equals("unknown") && text.contains(condition.asText())) score += 10;
        return score;
    }
}
