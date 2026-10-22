package com.example1.bddllm.config;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.evaluation.EvaluationService;
import com.example1.bddllm.github.GitHubIssueService;
import com.example1.bddllm.job.JobService;
import com.example1.bddllm.worker.AnalysisWorker;
import com.example1.bddllm.worker.JobQueue;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RoleRunner implements ApplicationRunner {
    private final GitHubIssueService issues;
    private final Environment env;
    private final ToolOperations tools;
    private final AnalysisWorker worker;
    private final JobQueue queue;
    private final JobService jobs;
    private final ConfigurableApplicationContext context;
    private final EvaluationService evaluation;

    public RoleRunner(
        Environment env,
        ToolOperations tools,
        AnalysisWorker worker,
        JobQueue queue,
        JobService jobs,
        ConfigurableApplicationContext context,
        EvaluationService evaluation,
        GitHubIssueService issues) {
        this.issues = issues;
        this.env = env;
        this.tools = tools;
        this.worker = worker;
        this.queue = queue;
        this.jobs = jobs;
        this.context = context;
        this.evaluation = evaluation;
    }

    public void run(ApplicationArguments args) throws Exception {
        String role = env.getProperty("bdd.role", "api");
        if (role.equals("migrate")) {
            System.out.println("database_migrated");
            context.close();
            return;
        }
        if (role.equals("tools")) {
            String command = env.getProperty("bdd.command", "db-status");
            long id = env.getProperty("bdd.job-id", Long.class, 0L);
            switch (command) {
                case "db-status" -> System.out.println(Json.encode(tools.databaseStatus()));
                case "slack-reset" -> System.out.println(
                    Json.encode(
                        Json.obj(
                            "notification_reset",
                            tools.resetNotification(
                                env.getRequiredProperty("bdd.team-id"),
                                env.getRequiredProperty("bdd.channel-id"),
                                id,
                                env.getRequiredProperty(
                                    "bdd.candidate-no",
                                    Integer.class)))));
                case "github-issue-retry" -> System.out.println(Json.encode(issues.retry(id,
                    env.getRequiredProperty("bdd.candidate-no", Integer.class))));
                case "status" -> System.out.println(Json.encode(jobs.get(id)));
                case "retry", "recover" -> {
                    queue.requeue(id, command.equals("recover"));
                    System.out.println(Json.encode(jobs.get(id)));
                }
                case "evaluate" -> {
                    ObjectNode report =
                        evaluation.run(
                            env.getProperty("bdd.suite", "samples/evaluation"),
                            env.getProperty("bdd.output", "artifacts/evaluation"),
                            env.getProperty("bdd.reference", Boolean.class, false));
                    System.out.println(Json.pretty(report));
                    if (!report.path("automated_pass").asBoolean()) {
                        exit(1);
                        return;
                    }
                }
                default -> throw new IllegalArgumentException("unknown_command");
            }
            context.close();
            return;
        }
        if (role.equals("worker")) {
            boolean once = env.getProperty("bdd.once", Boolean.class, false);
            Long target = env.getProperty("bdd.job-id", Long.class);
            long poll = env.getProperty("bdd.poll-ms", Long.class, 3000L);
            if (poll < 100) throw new IllegalArgumentException("invalid_poll_interval");
            do {
                ObjectNode out = worker.once(target);
                if (once || !out.path("status").asText().equals("idle"))
                    System.out.println(Json.encode(out));
                if (once) {
                    exit(
                        Set.of("failed", "retry_scheduled")
                            .contains(out.path("status").asText())
                            ? 1
                            : 0);
                    return;
                }
                if (out.path("status").asText().equals("idle")) Thread.sleep(poll);
            } while (context.isActive() && !Thread.currentThread().isInterrupted());
        }
    }

    private void exit(int code) {
        int actual = SpringApplication.exit(context, () -> code);
        if (actual != 0) System.exit(actual);
    }
}
