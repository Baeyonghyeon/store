package com.example1.bddllm.evidence;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

@Component
@ConditionalOnProperty(name = "bdd.role", havingValue = "api", matchIfMissing = true)
public class EvidenceRegistry {
    private final Map<String, ObjectNode> entries = new HashMap<>();

    public EvidenceRegistry(
        @Value("${bdd.evidence-config}") String config,
        @Value("${bdd.evidence-root}") String root)
        throws IOException {
        TomlParseResult data = Toml.parse(Path.of(config));
        if (data.hasErrors()
            || !data.keySet().equals(Set.of("version", "routes"))
            || !Objects.equals(data.getLong("version"), 1L))
            throw new IllegalStateException("invalid_evidence_config");
        TomlArray routes = data.getArray("routes");
        Path base = Path.of(root).toRealPath();
        if (routes == null || routes.isEmpty())
            throw new IllegalStateException("missing_evidence_routes");
        for (int routeIndex = 0; routeIndex < routes.size(); routeIndex++) {
            TomlTable routeConfiguration = routes.getTable(routeIndex);
            Set<String> required = Set.of("service", "method", "route", "specification", "bdd");
            Set<String> allowed = Set.of("service", "method", "route", "specification", "bdd",
                "repository", "test_files", "support_files", "target_symbols", "search_terms");
            if (!routeConfiguration.keySet().containsAll(required) || !allowed.containsAll(routeConfiguration.keySet()))
                throw new IllegalStateException("invalid_evidence_route");
            for (String fieldName : required)
                if (routeConfiguration.getString(fieldName) == null || routeConfiguration.getString(fieldName).isBlank())
                    throw new IllegalStateException("invalid_evidence_route");
            String key =
                key(
                    routeConfiguration.getString("service"),
                    routeConfiguration.getString("method").strip().toUpperCase(Locale.ROOT),
                    routeConfiguration.getString("route"));
            ObjectNode documents = Json.obj("specification", document(base, routeConfiguration.getString("specification")),
                "bdd", document(base, routeConfiguration.getString("bdd")));
            if (!required.containsAll(routeConfiguration.keySet())) {
                TestSourceConfig sources = new TestSourceConfig(routeConfiguration.getString("repository"),
                    strings(routeConfiguration, "test_files", true), strings(routeConfiguration, "support_files", false),
                    strings(routeConfiguration, "target_symbols", false), strings(routeConfiguration, "search_terms", false));
                documents.set("test_source_config", sources.json());
            }
            if (entries.putIfAbsent(key, documents) != null)
                throw new IllegalStateException("duplicate_evidence_route");
        }
    }

    private static List<String> strings(TomlTable table, String name, boolean required) {
        TomlArray values = table.getArray(name);
        if (values == null) {
            if (required) throw new IllegalStateException("missing_test_files");
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (int valueIndex = 0; valueIndex < values.size(); valueIndex++) result.add(values.getString(valueIndex));
        return List.copyOf(result);
    }

    private String key(String service, String method, String route) {
        return Json.encode(List.of(service, method, route));
    }

    public ObjectNode forEvent(ErrorEvent errorEvent) {
        ObjectNode documents = entries.get(key(errorEvent.service(), errorEvent.method(), errorEvent.route()));
        if (documents == null)
            throw new Problem(422, "evidence_not_mapped", "이 서비스·메서드·라우트에 연결된 명세와 BDD가 없습니다.");
        return documents.deepCopy();
    }

    public static ObjectNode document(Path root, String name) throws IOException {
        Path relative = Path.of(name);
        if (relative.isAbsolute()
            || StreamSupport.stream(relative.spliterator(), false)
            .anyMatch(part -> part.toString().equals("..")))
            throw new IOException("invalid_document_path");
        Path real = root.resolve(relative).toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real))
            throw new IOException("invalid_document_path");
        String content = Files.readString(real).replace("\r\n", "\n").replace('\r', '\n');
        if (content.isBlank()) throw new IOException("empty_document");
        return Json.obj("path", real.toString(), "content", content, "sha256", Json.sha(content));
    }
}
