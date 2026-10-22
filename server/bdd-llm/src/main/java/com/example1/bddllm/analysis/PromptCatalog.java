package com.example1.bddllm.analysis;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Immutable versioned resources shared by registration and analysis. Never accepts prompt bodies from callers. */
@Component
public final class PromptCatalog {
    private static final Pattern FILE_NAME = Pattern.compile("[a-z][a-z0-9-]*-v[1-9][0-9]*\\.txt");
    private final Environment environment;
    private final Map<String, Template> templates;

    public record Template(String fileName, String content, String sha256) {}

    public PromptCatalog(Environment environment) {
        this.environment = environment;
        String directory = environment.getProperty("bdd.prompts.root", "classpath:prompts/");
        if (!directory.startsWith("classpath:") && !directory.startsWith("file:"))
            throw new IllegalStateException("invalid_prompt_directory");
        if (!directory.endsWith("/")) directory += "/";
        Map<String, Template> loaded = new LinkedHashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(directory + "*.txt")) {
                String fileName = resource.getFilename();
                if (fileName == null || !FILE_NAME.matcher(fileName).matches())
                    throw new IllegalStateException("prompt_file_must_have_version");
                String content;
                try (InputStream input = resource.getInputStream()) {
                    content = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
                }
                if (content.isBlank() || loaded.putIfAbsent(fileName, new Template(fileName, content, Json.sha(content))) != null)
                    throw new IllegalStateException("invalid_or_duplicate_prompt_file");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("prompt_catalog_unavailable", exception);
        }
        if (loaded.isEmpty()) throw new IllegalStateException("empty_prompt_catalog");
        templates = Map.copyOf(loaded);
    }

    public Template select(boolean withTests) {
        String fileName = environment.getProperty(withTests ? "bdd.prompts.test-file" : "bdd.prompts.document-file",
                withTests ? "bdd-test-analysis-v1.txt" : "bdd-document-analysis-v1.txt");
        Template template = templates.get(fileName);
        if (template == null)
            throw new Problem(422, "analysis_registration_failed", "설정한 버전의 프롬프트 파일이 없습니다.");
        return template;
    }

    public Template resolve(String fileName, String expectedHash) {
        Template template = templates.get(fileName);
        if (template == null) throw new LlmFailure("prompt_file_missing");
        if (!template.sha256().equals(expectedHash)) throw new LlmFailure("prompt_hash_mismatch");
        return template;
    }
}
