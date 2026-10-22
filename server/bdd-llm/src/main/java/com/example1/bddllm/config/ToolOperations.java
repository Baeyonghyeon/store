package com.example1.bddllm.config;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;
import com.example1.bddllm.event.ErrorEventEntityRepository;
import com.example1.bddllm.event.ErrorGroupRepository;
import com.example1.bddllm.job.AnalysisJobRepository;
import com.example1.bddllm.slack.SlackChannelLockRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ToolOperations {
    private final ErrorGroupRepository groups;
    private final ErrorEventEntityRepository events;
    private final AnalysisJobRepository jobs;
    private final SlackChannelLockRepository locks;

    public ToolOperations(
        ErrorGroupRepository groups,
        ErrorEventEntityRepository events,
        AnalysisJobRepository jobs,
        SlackChannelLockRepository locks) {
        this.groups = groups;
        this.events = events;
        this.jobs = jobs;
        this.locks = locks;
    }

    @Transactional(readOnly = true)
    public ObjectNode databaseStatus() {
        ObjectNode summary =
            Json.obj(
                "group_count",
                groups.count(),
                "event_count",
                events.count(),
                "occurrence_count",
                groups.totalOccurrences(),
                "job_count",
                jobs.count());
        ObjectNode counts = Json.obj("pending", 0, "running", 0, "succeeded", 0, "failed", 0);
        for (AnalysisJobRepository.StatusCount count : jobs.countByStatus())
            counts.put(count.getStatus(), count.getCount());
        summary.set("job_counts", counts);
        return summary;
    }

    public boolean resetNotification(String team, String channel, long job, int candidate) {
        if (!team.matches("T[A-Z0-9]+")
            || !channel.matches("[CG][A-Z0-9]+")
            || job < 1
            || candidate < 0
            || candidate > 5) throw Problem.invalid();
        return locks.resetRetryable(team, channel, job, candidate);
    }
}
