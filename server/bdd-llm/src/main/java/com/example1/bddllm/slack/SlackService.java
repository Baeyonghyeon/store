package com.example1.bddllm.slack;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.review.ReviewService;
import com.example1.bddllm.github.GitHubIssueService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(name = "bdd.role", havingValue = "slack")
public class SlackService implements SmartLifecycle {
    private final GitHubIssueService issues;
    private final SlackApi api;
    private final SlackNotificationRepository notifications;
    private final ReviewService reviews;
    private final Environment env;
    private final SlackChannelLockRepository channelLocks;
    private final ConfigurableApplicationContext context;
    private final SlackMetadata metadata;
    private final String channel;
    private final Set<String> reviewers;
    private String team;
    private SlackChannelLockRepository.Lease channelLease;
    private volatile boolean running;
    private final ExecutorService actions =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().name("slack-action-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService poller =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("slack-notifications").factory());

    public SlackService(
        SlackApi api,
        SlackNotificationRepository notifications,
        ReviewService reviews,
        Environment env,
        SlackChannelLockRepository channelLocks,
        ConfigurableApplicationContext context,
        GitHubIssueService issues) {
        this.issues = issues;
        this.api = api;
        this.notifications = notifications;
        this.reviews = reviews;
        this.env = env;
        this.channelLocks = channelLocks;
        this.context = context;
        metadata = new SlackMetadata(env.getProperty("SLACK_APP_TOKEN", ""));
        channel = env.getProperty("SLACK_CHANNEL_ID", "");
        if (!channel.matches("[CG][A-Z0-9]+"))
            throw new IllegalArgumentException("invalid_slack_channel");
        String signing = env.getProperty("SLACK_SIGNING_SECRET", "");
        if (signing.isBlank()) throw new IllegalArgumentException("SLACK_SIGNING_SECRET을 설정하세요.");
        reviewers = new HashSet<>();
        for (String user : env.getProperty("SLACK_REVIEWER_IDS", "").split(","))
            if (!user.isBlank()) {
                if (!user.strip().matches("[UW][A-Z0-9]+"))
                    throw new IllegalArgumentException("invalid_slack_reviewer");
                reviewers.add(user.strip());
            }
    }

    private final Semaphore actionCapacity = new Semaphore(34);

    public ActionReservation reserve(Runnable action) {
        if (!actionCapacity.tryAcquire()) throw new RejectedExecutionException("slack_busy");
        return new ActionReservation(action);
    }

    public final class ActionReservation implements Runnable, AutoCloseable {
        private final Runnable action;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private ActionReservation(Runnable action) {
            this.action = action;
        }

        public void run() {
            if (consumed.compareAndSet(false, true))
                try {
                    actions.execute(
                        () -> {
                            try {
                                action.run();
                            } finally {
                                actionCapacity.release();
                            }
                        });
                } catch (RuntimeException exception) {
                    actionCapacity.release();
                    throw exception;
                }
        }

        public void close() {
            if (consumed.compareAndSet(false, true)) actionCapacity.release();
        }
    }

    public void start() {
        if (running) return;
        try {
            team = api.call("auth.test", Json.obj()).path("team_id").asText();
            if (!team.matches("T[A-Z0-9]+")) throw new IllegalStateException("invalid_slack_team");
            channelLease = channelLocks.acquire(team, channel);
            notifications.markInterrupted(team, channel);
            running = true;
            long interval = env.getProperty("SLACK_POLL_SECONDS", Long.class, 5L);
            if (interval < 1) throw new IllegalArgumentException("invalid_slack_poll");
            if (env.getProperty("bdd.slack.poll-enabled", Boolean.class, true))
                poller.scheduleWithFixedDelay(
                    () -> {
                        try {
                            if (!channelLease.isValid())
                                throw new IllegalStateException("slack_lock_lost");
                            pollOnce();
                        } catch (Exception exception) {
                            LoggerFactory.getLogger(getClass())
                                .error("slack_service_stopped_check_database");
                            context.close();
                        }
                    },
                    0,
                    interval,
                    TimeUnit.SECONDS);
        } catch (Exception exception) {
            stop();
            throw new IllegalStateException("Slack 설정·인증·알림 잠금을 확인해 주세요.");
        }
    }

    public void stop() {
        running = false;
        poller.shutdownNow();
        actions.shutdown();
        try {
            actions.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (channelLease != null) {
            try {
                channelLease.close();
            } catch (RuntimeException ignored) {
            } finally {
                channelLease = null;
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private String identity(JsonNode body) {
        String user = body.path("user").path("id").asText();
        if (!body.path("team").path("id").asText().equals(team)
            || !user.matches("[UW][A-Z0-9]+")
            || !reviewers.isEmpty() && !reviewers.contains(user)) throw Problem.invalid();
        return user;
    }

    private ObjectNode coordinates(String raw) {
        ObjectNode data = metadata.verify(raw);
        if (!data.path("team_id").asText().equals(team)
            || !data.path("channel_id").asText().equals(channel)) throw Problem.invalid();
        Json.integer(data, "job_id", 1, Long.MAX_VALUE);
        Json.integer(data, "candidate_no", 1, 5);
        Json.integer(data, "revision", 0, Long.MAX_VALUE);
        return data;
    }

    private void registered(JsonNode data, String ts) {
        if (!ts.matches("[0-9]{1,20}\\.[0-9]{1,20}")
            || !notifications
            .existsByTeamIdAndChannelIdAndJobIdAndCandidateNoAndStateAndMessageTs(
                team,
                channel,
                data.path("job_id").asLong(),
                data.path("candidate_no").asInt(),
                "sent",
                ts)) throw Problem.invalid();
    }

    private ObjectNode candidate(JsonNode data, ObjectNode detail) {
        int number = data.path("candidate_no").asInt();
        if (number > detail.path("candidates").size()) throw Problem.invalid();
        ObjectNode candidate = detail.path("candidates").get(number - 1).deepCopy();
        if (candidate.path("revision").asLong() != data.path("revision").asLong())
            throw ReviewService.conflict();
        return candidate;
    }

    public void action(ObjectNode body) {
        String viewId = null, user = null;
        try {
            user = identity(body);
            JsonNode actionPayload = body.path("actions").get(0);
            String action = actionPayload.path("action_id").asText().replaceFirst("^bdd_", "");
            if (!Set.of("approve", "edit", "reject", "evidence").contains(action))
                throw Problem.invalid();
            ObjectNode data = coordinates(actionPayload.path("value").asText());
            if (!data.path("kind").asText().equals("message")
                || !body.path("channel").path("id").asText().equals(channel))
                throw Problem.invalid();
            String ts = body.path("message").path("ts").asText();
            viewId =
                api.call(
                        "views.open",
                        Json.obj(
                            "trigger_id",
                            body.path("trigger_id"),
                            "view",
                            SlackViews.notice("후보 검토", "저장된 후보와 근거를 불러오는 중입니다.")))
                    .path("view")
                    .path("id")
                    .asText();
            registered(data, ts);
            ObjectNode reviewDetail = reviews.detail(data.path("job_id").asLong());
            ObjectNode candidate = candidate(data, reviewDetail);
            data.put("kind", "modal")
                .put("action", action)
                .put("user_id", user)
                .put("message_ts", ts);
            api.call(
                "views.update",
                Json.obj(
                    "view_id",
                    viewId,
                    "view",
                    SlackViews.modal(reviewDetail, candidate, action, metadata.sign(data))));
        } catch (Exception exception) {
            if (viewId != null)
                safeCall(
                    "views.update",
                    Json.obj(
                        "view_id",
                        viewId,
                        "view",
                        SlackViews.notice("검토를 열 수 없습니다", errorMessage(exception))));
            else if (user != null)
                safeCall(
                    "chat.postEphemeral",
                    Json.obj("channel", channel, "user", user, "text", errorMessage(exception)));
        }
    }

    public void submit(ObjectNode body) {
        JsonNode view = body.path("view");
        boolean saved = false;
        try {
            String user = identity(body);
            ObjectNode data = coordinates(view.path("private_metadata").asText());
            String action = data.path("action").asText();
            if (!data.path("kind").asText().equals("modal")
                || !data.path("user_id").asText().equals(user)
                || !Set.of("approve", "edit", "reject").contains(action))
                throw Problem.invalid();
            registered(data, data.path("message_ts").asText());
            ObjectNode reviewDetail = reviews.detail(data.path("job_id").asLong());
            ObjectNode candidate = candidate(data, reviewDetail);
            reviews.submitFromSlack(
                data.path("job_id").asLong(),
                data.path("candidate_no").asInt(),
                SlackViews.submission(
                    view,
                    candidate.get("current"),
                    action,
                    "slack:" + team + ":" + user,
                    data.path("revision").asLong()));
            saved = true;
        } catch (Exception exception) {
            if (view.hasNonNull("id"))
                safeCall(
                    "views.update",
                    Json.obj(
                        "view_id",
                        view.path("id"),
                        "view",
                        SlackViews.restore(
                            view,
                            errorMessage(exception),
                            exception instanceof Problem problem
                                && problem.code.equals("review_conflict"))));
        }
        if (saved && view.hasNonNull("id"))
            safeCall(
                "views.update",
                Json.obj(
                    "view_id",
                    view.path("id"),
                    "view",
                    SlackViews.notice(
                        "검토 저장 완료", "검토 결과와 이력을 저장했습니다. Slack 메시지는 다음 확인 주기에 갱신됩니다.")));
    }

    private String errorMessage(Exception exception) {
        return exception instanceof Problem problem
            ? problem.getMessage()
            : "처리 결과를 확인하지 못했습니다. 검토 이력과 최신 메시지를 확인해 주세요.";
    }

    private void safeCall(String method, ObjectNode body) {
        try {
            api.call(method, body);
        } catch (Exception exception) {
            LoggerFactory.getLogger(getClass()).warn("slack_view_update_failed");
        }
    }

    private ObjectNode message(ObjectNode detail, JsonNode candidate) {
        String signed =
            candidate == null
                ? null
                : metadata.sign(
                Json.obj(
                    "kind",
                    "message",
                    "team_id",
                    team,
                    "channel_id",
                    channel,
                    "job_id",
                    detail.path("job_id"),
                    "candidate_no",
                    candidate.path("candidate_no"),
                    "revision",
                    candidate.path("revision")));
        if (detail.has("test_evidence")) detail.put("evidence_url",
            env.getProperty("BDD_PUBLIC_API_URL", "http://localhost:8000").replaceAll("/+$", "")
                + "/jobs/" + detail.path("job_id").asLong() + "/evidence");
        return SlackViews.message(detail, candidate, signed);
    }

    public ObjectNode pollOnce() {
        long minimum = env.getProperty("SLACK_MIN_JOB_ID", Long.class, 1L);
        if (minimum < 1) throw Problem.invalid();
        issues.dispatchOnce();
        int sent = 0, updated = 0, failed = 0;
        for (SlackNotificationRepository.PendingNotification next :
            notifications.findUnsent(team, channel, minimum)) {
            long job = next.getJobId();
            int number = next.getCandidateNo();
            ObjectNode detail = reviews.detail(job);
            JsonNode candidate = number == 0 ? null : detail.path("candidates").get(number - 1);
            ObjectNode body = message(detail, candidate);
            body.put("channel", channel);
            if (notifications.reserve(team, channel, job, number) != 1) continue;
            ObjectNode response;
            try {
                response = api.call("chat.postMessage", body);
                if (!response.path("channel").asText().equals(channel)
                    || !response.path("ts").asText().matches("[0-9]{1,20}\\.[0-9]{1,20}"))
                    throw Problem.invalid();
            } catch (SlackApi.Rejected error) {
                notifications.failDelivery(team, channel, job, number, "failed", error.code);
                failed++;
                continue;
            } catch (Exception error) {
                notifications.failDelivery(
                    team, channel, job, number, "uncertain", "delivery_unknown");
                failed++;
                continue;
            }
            if (notifications.markSent(
                team,
                channel,
                job,
                number,
                response.path("ts").asText(),
                candidate == null ? 0 : candidate.path("revision").asInt())
                != 1) throw new IllegalStateException("slack_delivery_record_lost");
            sent++;
        }
        for (SlackNotification notification :
            notifications.findOutdated(team, channel, PageRequest.of(0, 5))) {
            ObjectNode detail = reviews.detail(notification.jobId);
            JsonNode candidate = detail.path("candidates").get(notification.candidateNo - 1);
            ObjectNode body = message(detail, candidate);
            body.put("channel", channel).put("ts", notification.messageTs);
            try {
                api.call("chat.update", body);
            } catch (Exception error) {
                continue;
            }
            notifications.advanceRevision(notification.id, candidate.path("revision").asInt(), candidate.path("github_issue").path("version").asLong(-1));
            updated++;
        }
        return Json.obj("sent", sent, "updated", updated, "failed", failed);
    }
}
