package com.example1.bddllm.slack;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonParser;
import com.slack.api.app_backend.SlackSignature;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.jakarta_servlet.SlackAppServlet;
import com.slack.api.bolt.middleware.builtin.RequestVerification;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConditionalOnProperty(name = "bdd.role", havingValue = "slack")
public class SlackHttpConfiguration {
    private static final ThreadLocal<List<SlackService.ActionReservation>> AFTER_ACK =
        new ThreadLocal<>();

    private static void afterAck(SlackService service, Runnable action) {
        List<SlackService.ActionReservation> pending = AFTER_ACK.get();
        SlackService.ActionReservation reservation = service.reserve(action);
        if (pending == null) reservation.run();
        else pending.add(reservation);
    }

    @Bean(destroyMethod = "stop")
    App boltApp(Environment env, SlackService service) {
        AppConfig config =
            AppConfig.builder()
                .singleTeamBotToken(env.getProperty("SLACK_BOT_TOKEN"))
                .signingSecret(env.getProperty("SLACK_SIGNING_SECRET"))
                .appInitializersEnabled(false)
                .build();
        // We perform workspace/user/message authorization in SlackService; Bolt verifies the raw
        // HTTP signature.
        App app =
            new App(
                config,
                List.of(
                    new RequestVerification(
                        new SlackSignature.Verifier(
                            new SlackSignature.Generator(
                                env.getProperty(
                                    "SLACK_SIGNING_SECRET"))))));
        for (String action : List.of("approve", "edit", "reject", "evidence"))
            app.blockAction(
                "bdd_" + action,
                (request, ctx) -> {
                    ObjectNode payload = payload(request.getRequestBodyAsString());
                    afterAck(service, () -> service.action(payload));
                    return ctx.ack();
                });
        app.blockAction("bdd_source_link", (request, ctx) -> ctx.ack());
        app.blockAction("bdd_github_issue_link", (request, ctx) -> ctx.ack());
        app.viewSubmission(
            "bdd_review_submit",
            (request, ctx) -> {
                ObjectNode payload = payload(request.getRequestBodyAsString());
                afterAck(service, () -> service.submit(payload));
                return ctx.ack(
                    JsonParser.parseString(
                        Json.encode(
                            Json.obj(
                                "response_action",
                                "update",
                                "view",
                                SlackViews.notice(
                                    "검토 저장 중",
                                    "완료 안내가 표시될 때까지 기다려 주세요.")))));
            });
        return app;
    }

    @Bean
    ServletRegistrationBean<SlackAppServlet> slackServlet(App app) {
        return new ServletRegistrationBean<>(
            new SlackAppServlet(app) {
                @Override
                protected void doPost(HttpServletRequest request, HttpServletResponse response)
                    throws IOException {
                    List<SlackService.ActionReservation> pending =
                        new ArrayList<SlackService.ActionReservation>();
                    AFTER_ACK.set(pending);
                    boolean flushed = false;
                    try {
                        super.doPost(request, response);
                        response.flushBuffer();
                        flushed = true;
                    } finally {
                        AFTER_ACK.remove();
                        if (!flushed)
                            for (SlackService.ActionReservation action : pending)
                                action.close();
                    }
                    for (SlackService.ActionReservation action : pending) action.run();
                }
            },
            "/slack/events");
    }

    public static ObjectNode payload(String body) {
        if (body.stripLeading().startsWith("{")) return (ObjectNode) Json.parse(body);
        for (String field : body.split("&")) {
            int equals = field.indexOf('=');
            if (equals > 0
                && URLDecoder.decode(field.substring(0, equals), StandardCharsets.UTF_8)
                .equals("payload"))
                return (ObjectNode)
                    Json.parse(
                        URLDecoder.decode(
                            field.substring(equals + 1), StandardCharsets.UTF_8));
        }
        throw Problem.invalid();
    }
}
