package com.example1.bddllm.evidence;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.time.Duration;
import java.util.Base64;

@Component
public class GitHubSourceClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final OkHttpClient client;
    private final String base;

    public GitHubSourceClient(@Value("${bdd.github-base-url:https://api.github.com}") String base) {
        this.base = base.replaceAll("/+$", "");
        this.client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5)).callTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(10)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    }

    public ArrayNode fetch(TestSourceConfig config, String sha) {
        if (!sha.matches("[0-9a-fA-F]{40}"))
            throw new Problem(422, "source_commit_invalid", "테스트 근거 수집에는 전체 Git 커밋 SHA가 필요합니다.");
        JsonNode commit = get("/repos/" + config.repository() + "/commits/" + sha, true);
        if (!commit.path("sha").asText().equalsIgnoreCase(sha)) throw invalidResponse();
        ArrayNode documents = Json.array();
        for (String path : config.files()) {
            JsonNode file = get("/repos/" + config.repository() + "/contents/" + path + "?ref=" + sha, false);
            if (!file.path("type").asText().equals("file") || file.has("submodule_git_url")
                || !file.path("path").asText().equals(path) || !file.path("encoding").asText().equals("base64"))
                throw invalidResponse();
            try {
                byte[] bytes = Base64.getDecoder().decode(file.path("content").asText().replace("\n", "").replace("\r", ""));
                if (bytes.length > 1024 * 1024)
                    throw new Problem(422, "source_file_too_large", "테스트 파일이 1MiB 한도를 넘습니다.");
                String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (content.isBlank()) throw invalidResponse();
                documents.add(Json.obj("path", path, "content", content, "sha256", Json.sha(content),
                    "kind", config.testFiles().contains(path) ? "test" : "support", "status", "collected"));
            } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException error) {
                throw invalidResponse();
            }
        }
        return documents;
    }

    private JsonNode get(String path, boolean commit) {
        Request request = new Request.Builder().url(base + path)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "bdd-gap-analyzer").get().build();
        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) throw invalidResponse();
            try (InputStream body = response.body().byteStream()) {
                int status = response.code();
                if (status == 429 || status == 403 && ("0".equals(response.header("X-RateLimit-Remaining"))
                    || response.header("Retry-After") != null))
                    throw new Problem(503, "source_rate_limited", "GitHub 요청 한도에 도달했습니다. 잠시 뒤 다시 접수해 주세요.");
                if (status == 404 || commit && status == 422)
                    throw new Problem(422, commit ? "source_commit_not_found" : "source_file_not_found",
                        commit ? "GitHub에 해당 저장소·배포 커밋이 없습니다. 공개 저장소에 push되었는지 확인해 주세요."
                            : "해당 배포 커밋에 지정한 테스트 파일이 없습니다. 경로를 확인해 주세요.");
                if (status == 401 || status == 403)
                    throw new Problem(422, "source_access_denied", "GitHub 공개 저장소를 읽을 수 없습니다.");
                if (status != 200) throw new Problem(503, "source_unavailable", "GitHub 근거 수집을 완료하지 못했습니다.");
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES)
                    throw new Problem(422, "source_file_too_large", "GitHub 응답 크기 한도를 넘었습니다.");
                try {
                    return Json.parse(new String(bytes, StandardCharsets.UTF_8));
                } catch (Problem error) {
                    throw invalidResponse();
                }
            }
        } catch (IOException error) {
            throw new Problem(503, "source_unavailable", "GitHub 근거 수집 연결에 실패했습니다.");
        }
    }

    private Problem invalidResponse() {
        return new Problem(503, "source_invalid_response", "GitHub 파일 응답을 확인할 수 없습니다.");
    }
}
