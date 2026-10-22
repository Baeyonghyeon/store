package com.example1.bddllm.evidence;

import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

@Service
public class SourceCollectionService {
    private final SourceSnapshotRepository snapshots;
    private final GitHubSourceClient github;
    private final Object[] locks = new Object[64];
    public SourceCollectionService(SourceSnapshotRepository snapshots, GitHubSourceClient github) {
        this.snapshots = snapshots;
        this.github = github;
        for (int attemptIndex = 0; attemptIndex < locks.length; attemptIndex++) locks[attemptIndex] = new Object();
    }
    private static void validate(TestSourceConfig config, ArrayNode documents) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (JsonNode document : documents) {
            String path = Json.text(document, "path");
            if (!paths.add(path) || !config.files().contains(path)
                    || !Json.sha(Json.text(document, "content")).equals(document.path("sha256").asText())
                    || !document.path("kind").asText().equals(config.testFiles().contains(path) ? "test" : "support"))
                throw new IllegalStateException("invalid_source_cache");
        }
        if (paths.size() != config.files().size()) throw new IllegalStateException("incomplete_source_cache");
    }
    public ArrayNode collect(TestSourceConfig config, String sha) {
        String canonicalSha = sha.toLowerCase(java.util.Locale.ROOT);
        String key = Json.sha(Json.canonical(Json.obj("version", "1", "repository", config.repository(),
                "sha", canonicalSha, "test_files", config.testFiles().stream().sorted().toList(),
                "support_files", config.supportFiles().stream().sorted().toList())));
        // The bounded stripe prevents a same-process request stampede without keeping a DB transaction open.
        synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
            SourceSnapshot snapshot = snapshots.findById(key).orElse(null);
            if (snapshot == null) {
                ArrayNode documents = github.fetch(config, canonicalSha);
                validate(config, documents);
                snapshots.insertIfAbsent(key, config.repository(), canonicalSha, Json.encode(documents));
                snapshot = snapshots.findById(key).orElseThrow();
            }
            ArrayNode documents = (ArrayNode) Json.parse(snapshot.documents);
            if (!snapshot.repository.equals(config.repository()) || !snapshot.commitSha.equals(canonicalSha))
                throw new IllegalStateException("invalid_source_cache");
            validate(config, documents);
            return documents;
        }
    }
}
