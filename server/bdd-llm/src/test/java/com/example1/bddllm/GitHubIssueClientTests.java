package com.example1.bddllm;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.github.GitHubIssueClient;
import com.example1.bddllm.github.GitHubIssueSettings;
import com.example1.bddllm.github.GitHubIssueBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class GitHubIssueClientTests {
    GitHubIssueClient client(HttpServer server) {
        return new GitHubIssueClient(new GitHubIssueSettings(new MockEnvironment()
            .withProperty("bdd.github-issues.token","fixture-issue-token")
            .withProperty("bdd.github-issues.api-base-url","http://127.0.0.1:"+server.getAddress().getPort())));
    }
    @Test void creationAndUpdateUseExpectedMethodsAndOnlyIssuePayload() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        List<String> requests=new CopyOnWriteArrayList<>();
        AtomicReference<JsonNode> payload=new AtomicReference<>();
        server.createContext("/",exchange->{
            requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-issue-token");
            assertThat(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version")).isEqualTo("2026-03-10");
            payload.set(Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] response=Json.encode(Json.obj("number",88,"html_url","https://github.com/o/r/issues/88")).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestMethod().equals("POST")?201:200,response.length);
            exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        GitHubIssueClient client=client(server);
        try {
            assertThat(client.publish("o/r",null,"title","body",1).number()).isEqualTo(88);
            client.publish("o/r",88L,"edited","new body",3);
            assertThat(requests).containsExactly("POST /repos/o/r/issues","PATCH /repos/o/r/issues/88");
            assertThat(payload.get()).isEqualTo(Json.obj("title","edited","body","new body"));
        } finally { client.close(); server.stop(0); }
    }
    @Test void errorsAreSanitizedAndUnknownCreateIsNeverInternallyRetried() throws Exception {
        for(int status:List.of(401,403,404,410,422,429,500,503)) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            AtomicInteger requests=new AtomicInteger();
            server.createContext("/",exchange->{
                requests.incrementAndGet();
                byte[] response="fixture-issue-token must not appear in errors".getBytes(StandardCharsets.UTF_8);
                if(status==503) exchange.getResponseHeaders().set("Retry-After","0");
                exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);exchange.close();
            });server.start();
            GitHubIssueClient client=client(server);
            try {
                assertThatThrownBy(()->client.publish("o/r",null,"title","body",1))
                    .isInstanceOfSatisfying(GitHubIssueClient.Failure.class,failure->{
                        assertThat(failure.getMessage()).doesNotContain("fixture-issue-token");
                        assertThat(failure.uncertain).isEqualTo(status>=500);
                        assertThat(failure.retryable).isEqualTo(status==429);
                    });
                assertThat(requests.get()).isEqualTo(1);
            } finally {client.close();server.stop(0);}
        }
    }
    @Test void connectionDropAfterPostDoesNotRepeatRequest() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger requests=new AtomicInteger();
        server.createContext("/",exchange->{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.close();});server.start();
        GitHubIssueClient client=client(server);
        try {
            assertThatThrownBy(()->client.publish("o/r",null,"title","body",1)).isInstanceOfSatisfying(GitHubIssueClient.Failure.class,
                failure->assertThat(failure.uncertain).isTrue());
            assertThat(requests.get()).isEqualTo(1);
        } finally {client.close();server.stop(0);}
    }
    @Test void reconciliationChecksClosedIssuesAndExactMarker() throws Exception {
        UUID marker=UUID.randomUUID();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> request=new AtomicReference<>();
        server.createContext("/",exchange->{
            request.set(exchange.getRequestURI().toString());
            byte[] response=Json.encode(List.of(Json.obj("number",88,"html_url","https://github.com/o/r/issues/88",
                "body",GitHubIssueBody.marker(marker)+"\n<!-- bdd-review:3 -->","state","closed"))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        GitHubIssueClient client=client(server);
        try {
            GitHubIssueClient.Receipt receipt=client.reconcile("o/r",marker,Instant.now());
            assertThat(receipt.number()).isEqualTo(88);assertThat(receipt.revision()).isEqualTo(3);
            assertThat(request.get()).contains("state=all","since=","page=1");
        } finally {client.close();server.stop(0);}
    }
    @Test void untrustedIssueUrlIsRejected() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] response=Json.encode(Json.obj("number",88,"html_url","https://evil.example/o/r/issues/88")).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        GitHubIssueClient client=client(server);
        try {
            assertThatThrownBy(()->client.publish("o/r",null,"title","body",1)).isInstanceOfSatisfying(GitHubIssueClient.Failure.class,
                failure->assertThat(failure.code).isEqualTo("github_invalid_response"));
        } finally {client.close();server.stop(0);}
    }
}
