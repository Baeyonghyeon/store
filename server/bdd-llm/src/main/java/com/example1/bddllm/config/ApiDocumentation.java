package com.example1.bddllm.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RestController
@ConditionalOnProperty(name = "bdd.role", havingValue = "api", matchIfMissing = true)
public class ApiDocumentation {
    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> schema() throws Exception {
        try (InputStream in = new ClassPathResource("openapi.json").getInputStream()) {
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
