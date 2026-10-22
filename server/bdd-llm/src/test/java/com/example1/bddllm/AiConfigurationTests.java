package com.example1.bddllm;

import com.example1.bddllm.analysis.AiConfiguration;
import com.example1.bddllm.analysis.AiConnectionProperties;
import com.example1.bddllm.analysis.AiProviderRegistry;
import com.example1.bddllm.analysis.LlmFailure;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AiConfigurationTests {
    private ApplicationContextRunner configured(String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AiConfiguration.class, AiProviderRegistry.class)
                .withInitializer(context -> {
                    try {
                        context.getEnvironment().getPropertySources().addLast(new YamlPropertySourceLoader()
                                .load("application", new ClassPathResource("application.yaml")).getFirst());
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                }).withPropertyValues(properties);
    }

    @Test
    void noProviderKeyIsRequiredForApiOrSlackStartup() {
        configured().run(context -> {
            assertThat(context).hasNotFailed();
            AiProviderRegistry registry = context.getBean(AiProviderRegistry.class);
            assertThat(registry.selectedProvider()).isEqualTo("ollama");
            assertThat(registry.selectedModel()).isEqualTo("gemma4:31b");
            assertThatThrownBy(() -> registry.get("ollama").chatModel())
                    .isInstanceOf(LlmFailure.class).extracting("code").isEqualTo("llm_auth");
        });
    }

    @Test
    void existingEnvironmentVariablesStillBindToSeparateProviderSettings() {
        configured("OLLAMA_MODEL=cloud-model", "DEEPSEEK_MODEL=deepseek-test", "OPENAI_MODEL=openai-test",
                "OLLAMA_API_KEY=fake-only-selected", "OLLAMA_BASE_URL=http://localhost:1234/v1").run(context -> {
            AiProviderRegistry registry = context.getBean(AiProviderRegistry.class);
            assertThat(registry.selectedModel()).isEqualTo("cloud-model");
            assertThat(registry.get("deepseek").defaultModel()).isEqualTo("deepseek-test");
            assertThat(registry.get("openai").defaultModel()).isEqualTo("openai-test");
            assertThat(context.getBean("ollamaCloudConnection", AiConnectionProperties.class).getBaseUrl())
                    .isEqualTo("http://localhost:1234/v1");
            registry.get("ollama").validate();
            assertThatThrownBy(() -> registry.get("openai").validate()).isInstanceOf(LlmFailure.class);
        });
    }

    @Test
    void providerAndModelCanBeSwitchedWithoutChangingAnalysisCode() {
        for (String provider : List.of("openai", "deepseek", "ollama")) {
            configured("LLM_PROVIDER=" + provider, "LLM_MODEL=explicit-model").run(context -> {
                AiProviderRegistry registry = context.getBean(AiProviderRegistry.class);
                assertThat(registry.selectedProvider()).isEqualTo(provider);
                assertThat(registry.selectedModel()).isEqualTo("explicit-model");
            });
        }
    }

    @Test
    void documentedSpringAiPropertiesAreUsedForOpenAiAndDeepSeek() {
        configured("LLM_PROVIDER=deepseek", "spring.ai.deepseek.api-key=fake-deepseek",
                "spring.ai.deepseek.base-url=https://api.deepseek.com/v1", "spring.ai.deepseek.timeout=12s",
                "spring.ai.deepseek.chat.model=from-standard-property").run(context -> {
            AiProviderRegistry registry = context.getBean(AiProviderRegistry.class);
            assertThat(registry.selectedModel()).isEqualTo("from-standard-property");
            OpenAiChatOptions options = (OpenAiChatOptions) registry.get("deepseek").chatModel().getOptions();
            assertThat(options.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(12));
            assertThat(options.getMaxRetries()).isZero();
            assertThat(options.getMaxTokens()).isEqualTo(6000);
        });
        configured("LLM_PROVIDER=openai", "spring.ai.openai.chat.model=openai-standard-model").run(context ->
                assertThat(context.getBean(AiProviderRegistry.class).selectedModel()).isEqualTo("openai-standard-model"));
    }

    @Test
    void commonModelOverrideTakesPrecedenceOverProviderModel() {
        configured("LLM_PROVIDER=deepseek", "LLM_MODEL=job-default", "spring.ai.deepseek.chat.model=provider-default")
                .run(context -> assertThat(context.getBean(AiProviderRegistry.class).selectedModel()).isEqualTo("job-default"));
    }

    @Test
    void unknownProviderAndInvalidTimeoutFailBeforeAnyCall() {
        configured("LLM_PROVIDER=unknown").run(context ->
                assertThatThrownBy(() -> context.getBean(AiProviderRegistry.class).selectedProvider())
                        .isInstanceOf(com.example1.bddllm.common.Problem.class));
        configured("OLLAMA_API_KEY=fake", "bdd.ai.ollama-cloud.timeout=0s").run(context ->
                assertThatThrownBy(() -> context.getBean(AiProviderRegistry.class).get("ollama").validate())
                        .isInstanceOf(LlmFailure.class).extracting("code").isEqualTo("llm_configuration"));
    }
}
