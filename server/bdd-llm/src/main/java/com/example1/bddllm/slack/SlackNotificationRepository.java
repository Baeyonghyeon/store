package com.example1.bddllm.slack;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SlackNotificationRepository extends JpaRepository<SlackNotification, Long> {
    interface PendingNotification {
        Long getJobId();

        Integer getCandidateNo();
    }

    boolean existsByTeamIdAndChannelIdAndJobIdAndCandidateNoAndStateAndMessageTs(
        String teamId,
        String channelId,
        long jobId,
        int candidateNo,
        String state,
        String messageTs);

    @Query(
        value =
            """
                WITH jobs AS (SELECT id,jsonb_array_length(result->'candidates') AS count FROM analysis_jobs WHERE status='succeeded' AND id>=:minimum)
                SELECT j.id AS "jobId",n.number AS "candidateNo" FROM jobs j CROSS JOIN LATERAL generate_series(CASE WHEN j.count=0 THEN 0 ELSE 1 END,j.count) n(number)
                WHERE j.count BETWEEN 0 AND 5 AND NOT EXISTS(SELECT 1 FROM slack_notifications s WHERE s.team_id=:team AND s.channel_id=:channel AND s.job_id=j.id AND s.candidate_no=n.number)
                ORDER BY j.id,n.number LIMIT 1
                """,
        nativeQuery = true)
    List<PendingNotification> findUnsent(String team, String channel, long minimum);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        value =
            "INSERT INTO slack_notifications(team_id,channel_id,job_id,candidate_no,state)"
                + " VALUES (:team,:channel,:job,:candidate,'sending') ON"
                + " CONFLICT(team_id,channel_id,job_id,candidate_no) DO NOTHING",
        nativeQuery = true)
    int reserve(String team, String channel, long job, int candidate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        "update SlackNotification n set"
            + " n.state='uncertain',n.errorCode='interrupted',n.updatedAt=CURRENT_TIMESTAMP"
            + " where n.teamId=:team and n.channelId=:channel and n.state='sending'")
    int markInterrupted(String team, String channel);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        "update SlackNotification n set"
            + " n.state=:state,n.errorCode=:code,n.updatedAt=CURRENT_TIMESTAMP where"
            + " n.teamId=:team and n.channelId=:channel and n.jobId=:job and"
            + " n.candidateNo=:candidate and n.state='sending'")
    int failDelivery(
        String team, String channel, long job, int candidate, String state, String code);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        "update SlackNotification n set"
            + " n.state='sent',n.messageTs=:ts,n.sentRevision=:revision,n.updatedAt=CURRENT_TIMESTAMP"
            + " where n.teamId=:team and n.channelId=:channel and n.jobId=:job and"
            + " n.candidateNo=:candidate and n.state='sending'")
    int markSent(String team, String channel, long job, int candidate, String ts, int revision);

    @Query(
        "select n from SlackNotification n where n.teamId=:team and n.channelId=:channel and"
            + " n.state='sent' and (exists(select r.id from CandidateReviewEntity r where"
            + " r.jobId=n.jobId and r.candidateNo=n.candidateNo and r.revision>n.sentRevision)"
            + " or exists(select delivery.id from GitHubIssueDelivery delivery where delivery.jobId=n.jobId"
            + " and delivery.candidateNo=n.candidateNo and delivery.version>n.issueVersion))"
            + " order by n.updatedAt,n.id")
    List<SlackNotification> findOutdated(String team, String channel, Pageable page);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        "update SlackNotification n set n.sentRevision=:revision,n.issueVersion=:issueVersion,n.updatedAt=CURRENT_TIMESTAMP"
            + " where n.id=:id and n.sentRevision<=:revision and n.issueVersion<=:issueVersion")
    int advanceRevision(long id, int revision, long issueVersion);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        "delete from SlackNotification n where n.teamId=:team and n.channelId=:channel and"
            + " n.jobId=:job and n.candidateNo=:candidate and n.state in"
            + " ('failed','uncertain')")
    int deleteRetryable(String team, String channel, long job, int candidate);
}
