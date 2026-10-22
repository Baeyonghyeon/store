package com.example1.bddllm;

import com.example1.bddllm.analysis.LlmFailure;
import com.example1.bddllm.analysis.PromptCatalog;
import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class PromptCatalogTests {
    @TempDir Path directory;

    @Test
    void packagedPromptsAreVersionedAndShareOriginalDocumentPolicy() {
        PromptCatalog catalog = new PromptCatalog(new MockEnvironment());
        PromptCatalog.Template document = catalog.select(false);
        PromptCatalog.Template tests = catalog.select(true);
        assertThat(document.fileName()).isEqualTo("bdd-document-analysis-v1.txt");
        assertThat(tests.fileName()).isEqualTo("bdd-test-analysis-v1.txt");
        assertThat(document.sha256()).isEqualTo(Json.sha(document.content()));
        assertThat(tests.content()).startsWith(document.content()).contains("test_assessments", "characterization_only");
    }

    @Test
    void filenameIsPartOfIdentityEvenWhenContentsAreEqual() throws Exception {
        Files.writeString(directory.resolve("document-v1.txt"), "same instructions");
        Files.writeString(directory.resolve("document-v2.txt"), "same instructions");
        MockEnvironment environment = environment().withProperty("bdd.prompts.document-file", "document-v1.txt");
        PromptCatalog catalog = new PromptCatalog(environment);
        PromptCatalog.Template original = catalog.select(false);
        environment.setProperty("bdd.prompts.document-file", "document-v2.txt");
        PromptCatalog.Template changed = catalog.select(false);
        assertThat(changed.fileName()).isNotEqualTo(original.fileName());
        assertThat(changed.sha256()).isEqualTo(original.sha256());
        assertThat(catalog.resolve(original.fileName(), original.sha256())).isEqualTo(original);
    }

    @Test
    void startupCatalogIsImmutableAndRestartDetectsChangedOldVersion() throws Exception {
        Path file = directory.resolve("document-v1.txt");
        Files.writeString(file, "original\r\n");
        MockEnvironment environment = environment().withProperty("bdd.prompts.document-file", "document-v1.txt");
        PromptCatalog catalog = new PromptCatalog(environment);
        PromptCatalog.Template original = catalog.select(false);
        assertThat(original.content()).isEqualTo("original\n");
        Files.writeString(file, "modified");
        assertThat(catalog.resolve(original.fileName(), original.sha256())).isEqualTo(original);
        PromptCatalog restarted = new PromptCatalog(environment);
        assertThatThrownBy(() -> restarted.resolve(original.fileName(), original.sha256()))
                .isInstanceOf(LlmFailure.class).extracting("code").isEqualTo("prompt_hash_mismatch");
    }

    @Test
    void missingVersionsAndPathTraversalDoNotFallBackToDefault() {
        MockEnvironment environment = new MockEnvironment();
        PromptCatalog catalog = new PromptCatalog(environment);
        assertThatThrownBy(() -> catalog.resolve("missing-v99.txt", "hash"))
                .isInstanceOf(LlmFailure.class).extracting("code").isEqualTo("prompt_file_missing");
        environment.setProperty("bdd.prompts.document-file", "../../some-file-v1.txt");
        assertThatThrownBy(() -> catalog.select(false)).isInstanceOf(Problem.class);
    }

    @Test
    void rejectsUnversionedOrBlankFilesAndNonlocalDirectories() throws Exception {
        Path invalid = directory.resolve("analyze.txt");
        Files.writeString(invalid, "instructions");
        assertThatThrownBy(() -> new PromptCatalog(environment())).hasMessage("prompt_file_must_have_version");
        Files.delete(invalid);
        Files.writeString(directory.resolve("document-v1.txt"), " ");
        assertThatThrownBy(() -> new PromptCatalog(environment())).hasMessage("invalid_or_duplicate_prompt_file");
        assertThatThrownBy(() -> new PromptCatalog(new MockEnvironment().withProperty("bdd.prompts.root", "https://example.com/")))
                .hasMessage("invalid_prompt_directory");
    }

    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("bdd.prompts.root", directory.toUri().toString());
    }
}
