package com.example1.bddllm.github;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;

@Component
public class GitHubIssueSettings {
    public final boolean enabled;
    public final String repository;
    public final String service;
    public final String token;
    public final String apiBase;
    public GitHubIssueSettings(Environment environment) {
        enabled = environment.getProperty("bdd.github-issues.enabled", Boolean.class, true);
        repository = environment.getProperty("bdd.github-issues.repository", "Baeyonghyeon/noteu");
        service = environment.getProperty("bdd.github-issues.service", "noteu");
        token = environment.getProperty("bdd.github-issues.token", "");
        apiBase = environment.getProperty("bdd.github-issues.api-base-url", "https://api.github.com").replaceAll("/+$", "");
        if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || service.isBlank())
            throw new IllegalArgumentException("invalid_github_issue_target");
        URI address = URI.create(apiBase);
        boolean localTest = "http".equals(address.getScheme()) && "127.0.0.1".equals(address.getHost());
        if (!apiBase.equals("https://api.github.com") && !localTest)
            throw new IllegalArgumentException("invalid_github_issue_api");
        if (address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null
            || address.getRawPath() != null && !address.getRawPath().isEmpty())
            throw new IllegalArgumentException("invalid_github_issue_api");
    }
}
