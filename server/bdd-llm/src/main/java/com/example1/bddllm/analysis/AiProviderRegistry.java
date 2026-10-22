package com.example1.bddllm.analysis;

import com.example1.bddllm.common.Problem;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class AiProviderRegistry {
    private final Map<String, AiProvider> providers = new LinkedHashMap<>();
    private final Environment environment;

    public AiProviderRegistry(List<AiProvider> configured, Environment environment) {
        this.environment = environment;
        for (AiProvider provider : configured) {
            if (providers.putIfAbsent(provider.id(), provider) != null)
                throw new IllegalArgumentException("duplicate_ai_provider");
        }
    }

    public AiProvider get(String id) {
        AiProvider provider = providers.get(id);
        if (provider == null) throw Problem.invalid();
        return provider;
    }

    public String selectedProvider() {
        String id = environment.getProperty("bdd.provider", "ollama").strip().toLowerCase(Locale.ROOT);
        return get(id).id();
    }

    public String selectedModel() {
        String override = environment.getProperty("bdd.model", "").strip();
        String model = override.isEmpty() ? get(selectedProvider()).defaultModel() : override;
        if (model.isBlank()) throw Problem.invalid();
        return model;
    }
}
