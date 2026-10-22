package com.example1.bddllm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchemaMigration {
    @Bean
    public FlywayMigrationStrategy migrationStrategy(
        @Value("${bdd.baseline-existing:false}") boolean baseline,
        @Value("${bdd.role:api}") String role,
        @Value("${bdd.test-migrate:false}") boolean testMigrate) {
        return flyway -> {
            if (!role.equals("migrate") && !testMigrate) {
                flyway.validate();
                if (flyway.info().current() == null || flyway.info().pending().length > 0)
                    throw new IllegalStateException("migrate 역할로 DB를 먼저 준비해 주세요.");
                return;
            }
            if (baseline && flyway.info().current() == null) {
                // Validate the schema before recording its baseline version.
                try {
                    flyway.baseline();
                } catch (RuntimeException error) {
                    throw new IllegalStateException("기존 스키마 검증에 실패하여 기준 버전을 등록하지 않았습니다.", error);
                }
            }
            flyway.migrate();
        };
    }
}
