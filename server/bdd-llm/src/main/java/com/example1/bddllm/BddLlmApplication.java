package com.example1.bddllm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;
import java.util.Set;

@SpringBootApplication
public class BddLlmApplication {
    public static void main(String[] args) {
        String role = System.getenv().getOrDefault("BDD_ROLE", "api");
        for (String arg : args) if (arg.startsWith("--bdd.role=")) role = arg.substring(11);
        if (!Set.of("api", "worker", "slack", "migrate", "tools").contains(role))
            throw new IllegalArgumentException("invalid_role");
        SpringApplication app = new SpringApplication(BddLlmApplication.class);
        app.setAdditionalProfiles(role);
        app.setWebApplicationType(
            Set.of("api", "slack").contains(role)
                ? WebApplicationType.SERVLET
                : WebApplicationType.NONE);
        app.setDefaultProperties(
            Map.of("bdd.role", role, "server.port", role.equals("slack") ? "8001" : "8000"));
        app.run(args);
    }
}
