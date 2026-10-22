package com.example1.bddllm.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

/**
 * Disabled unless an absolute directory is explicitly configured by a local operator.
 */
public final class AnalysisDiagnostics {
    private AnalysisDiagnostics() {
    }

    public static void write(String directory, long jobId, String kind, String content) {
        if (directory.isBlank()) return;
        try {
            Path root = Path.of(directory);
            if (!root.isAbsolute()) throw new IllegalArgumentException("diagnostics_directory_must_be_absolute");
            Files.createDirectories(root);
            Files.writeString(root.resolve("job-" + jobId + "-" + kind + ".txt"), content);
        } catch (IOException | IllegalArgumentException error) {
            org.slf4j.LoggerFactory.getLogger(AnalysisDiagnostics.class).warn("analysis_diagnostic_write_failed");
        }
    }
}
