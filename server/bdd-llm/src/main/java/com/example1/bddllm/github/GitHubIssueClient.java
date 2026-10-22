package com.example1.bddllm.github;

import com.example1.bddllm.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import okhttp3.*;
import okio.BufferedSink;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GitHubIssueClient {
    public record Receipt(long number, String url, int revision) {}
    public static final class Failure extends RuntimeException {
        public final String code;
        public final boolean uncertain;
        public final boolean retryable;
        public final long delaySeconds;
        public Failure(String code, boolean uncertain, boolean retryable, long delaySeconds) {
            super(code);
            this.code = code;
            this.uncertain = uncertain;
            this.retryable = retryable;
            this.delaySeconds = delaySeconds;
        }
    }
    private final GitHubIssueSettings settings;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(15)).callTimeout(Duration.ofSeconds(20))
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    public GitHubIssueClient(GitHubIssueSettings settings) { this.settings = settings; }
    @PreDestroy public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
    public Receipt publish(String repository, Long issueNumber, String title, String body, int revision) {
        boolean create = issueNumber == null;
        JsonNode response = request(create ? "POST" : "PATCH", "/repos/" + repository + "/issues" + (create ? "" : "/" + issueNumber),
            Json.obj("title", title, "body", body), create);
        Receipt receipt = receipt(repository, response, revision);
        if (issueNumber != null && receipt.number() != issueNumber) throw failure("github_invalid_response", false, true);
        return receipt;
    }
    public Receipt reconcile(String repository, UUID marker, Instant createdAt) {
        Receipt found = null;
        String since = URLEncoder.encode(createdAt.minusSeconds(300).toString(), StandardCharsets.UTF_8);
        for (int pageNumber = 1; pageNumber <= 20; pageNumber++) {
            JsonNode issues = request("GET", "/repos/" + repository + "/issues?state=all&sort=created&direction=desc&per_page=100&since=" + since + "&page=" + pageNumber, null, false);
            if (!issues.isArray()) throw failure("github_invalid_response", false, true);
            for (JsonNode issue : issues) {
                if (issue.has("pull_request") || !issue.path("body").asText().contains(GitHubIssueBody.marker(marker))) continue;
                if (found != null) throw failure("github_duplicate_marker", true, false);
                Matcher revision = Pattern.compile("<!-- bdd-review:([0-9]+) -->").matcher(issue.path("body").asText());
                int publishedRevision = 0;
                if (revision.find()) {
                    try { publishedRevision = Integer.parseInt(revision.group(1)); } catch (NumberFormatException ignored) {}
                }
                found = receipt(repository, issue, publishedRevision);
            }
            if (issues.size() < 100) return found;
        }
        throw failure("github_reconciliation_incomplete", true, false);
    }
    private Receipt receipt(String repository, JsonNode response, int revision) {
        long number = response.path("number").asLong();
        String url = response.path("html_url").asText();
        try {
            URI address = URI.create(url);
            if (number < 1 || !"https".equals(address.getScheme()) || !"github.com".equals(address.getHost())
                || address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null
                || !("/" + repository + "/issues/" + number).equalsIgnoreCase(address.getPath()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw failure("github_invalid_response", true, false); }
        return new Receipt(number, url, revision);
    }
    private JsonNode request(String method, String path, ObjectNode payload, boolean create) {
        if (settings.token.isBlank()) throw failure("github_not_configured", false, false);
        if (settings.token.contains("\r") || settings.token.contains("\n")) throw failure("github_auth", false, false);
        Request.Builder request = new Request.Builder().url(settings.apiBase + path)
            .header("Authorization", "Bearer " + settings.token).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10").header("User-Agent", "bdd-gap-analyzer");
        if (payload == null) request.get();
        else request.method(method, oneShotJson(payload));
        try (Response response = client.newCall(request.build()).execute()) {
            int status = response.code();
            if (status == 429 || status == 403 && ("0".equals(response.header("X-RateLimit-Remaining")) || response.header("Retry-After") != null)) {
                long delay = 60;
                try { delay = Long.parseLong(response.header("Retry-After", "60")); } catch (NumberFormatException ignored) {}
                throw new Failure("github_rate_limited", false, true, Math.max(1, Math.min(delay, 86400)));
            }
            if (status < 200 || status >= 300) {
                if (status >= 500) throw failure("github_server_error", create, !create);
                String code = switch (status) {
                    case 401, 403 -> "github_auth";
                    case 404 -> "github_repository_not_found";
                    case 410 -> "github_issues_disabled";
                    case 422 -> "github_invalid_issue";
                    default -> "github_request_rejected";
                };
                throw failure(code, false, false);
            }
            if (response.body() == null) throw failure("github_invalid_response", create, !create);
            byte[] bytes = response.body().byteStream().readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw failure("github_invalid_response", create, !create);
            try { return Json.parse(new String(bytes, StandardCharsets.UTF_8)); }
            catch (RuntimeException exception) { throw failure("github_invalid_response", create, !create); }
        } catch (IOException exception) { throw failure("github_delivery_unknown", create, !create); }
    }
    private static RequestBody oneShotJson(ObjectNode payload) {
        byte[] bytes = Json.encode(payload).getBytes(StandardCharsets.UTF_8);
        return new RequestBody() {
            @Override public MediaType contentType() { return MediaType.get("application/json; charset=utf-8"); }
            @Override public long contentLength() { return bytes.length; }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(bytes); }
        };
    }
    private static Failure failure(String code, boolean uncertain, boolean retryable) {
        return new Failure(code, uncertain, retryable, 30);
    }
}
