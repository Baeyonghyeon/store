package com.example1.bddllm.evidence;

import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Administrator-configured allowlist; never taken from an event or model output.
 */
public record TestSourceConfig(String repository, List<String> testFiles, List<String> supportFiles,
                               List<String> targetSymbols, List<String> searchTerms) {
    public TestSourceConfig {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("invalid_source_repository");
        testFiles = List.copyOf(testFiles);
        supportFiles = List.copyOf(supportFiles);
        targetSymbols = List.copyOf(targetSymbols);
        searchTerms = List.copyOf(searchTerms);
        if (testFiles.isEmpty() || testFiles.size() + supportFiles.size() > 20)
            throw new IllegalArgumentException("invalid_source_files");
        Set<String> unique = new HashSet<>();
        for (String path : java.util.stream.Stream.concat(testFiles.stream(), supportFiles.stream()).toList()) {
            if (!path.matches("[A-Za-z0-9_./$-]+\\.java") || path.startsWith("/")
                || List.of(path.split("/", -1)).stream().anyMatch(pathSegment -> pathSegment.isEmpty() || pathSegment.equals("..") || pathSegment.equals("."))
                || !unique.add(path)) throw new IllegalArgumentException("invalid_source_path");
        }
        if (targetSymbols.size() > 30 || searchTerms.size() > 30
            || targetSymbols.stream().anyMatch(targetSymbol -> targetSymbol.isBlank() || targetSymbol.length() > 100)
            || searchTerms.stream().anyMatch(searchTerm -> searchTerm.isBlank() || searchTerm.length() > 100))
            throw new IllegalArgumentException("invalid_search_terms");
    }

    public List<String> files() {
        return java.util.stream.Stream.concat(testFiles.stream(), supportFiles.stream()).sorted().toList();
    }

    public ObjectNode json() {
        return Json.obj("repository", repository, "test_files", testFiles, "support_files", supportFiles,
            "target_symbols", targetSymbols, "search_terms", searchTerms);
    }
}
