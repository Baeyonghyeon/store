package com.example1.bddllm.slack;

import com.example1.bddllm.common.Json;
import com.example1.bddllm.common.Problem;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

import org.hibernate.ConnectionAcquisitionMode;
import org.hibernate.ConnectionReleaseMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;

@Repository
public class SlackChannelLockRepository {
    private final EntityManagerFactory factory;
    private final SlackNotificationRepository notifications;
    @PersistenceContext
    private EntityManager entityManager;

    public SlackChannelLockRepository(
        EntityManagerFactory factory, SlackNotificationRepository notifications) {
        this.factory = factory;
        this.notifications = notifications;
    }

    public static long key(String team, String channel) {
        return ByteBuffer.wrap(
                HexFormat.of()
                    .parseHex(
                        Json.sha(
                            Json.canonical(
                                Json.value(
                                    List.of(
                                        "bdd-gap-slack",
                                        team,
                                        channel))))))
            .getLong();
    }

    public Lease acquire(String team, String channel) {
        // Session-level advisory locks must never move to another pooled DB connection.
        Session session =
            factory.unwrap(SessionFactory.class)
                .withOptions()
                .connectionHandling(
                    ConnectionAcquisitionMode.AS_NEEDED, ConnectionReleaseMode.ON_CLOSE)
                .openSession();
        long key = key(team, channel);
        boolean acquired = false;
        try {
            acquired =
                session.createNativeQuery("SELECT pg_try_advisory_lock(:key)", Boolean.class)
                    .setParameter("key", key)
                    .getSingleResult();
            if (!acquired) throw new IllegalStateException("slack_service_already_running");
            int backend =
                session.createNativeQuery("SELECT pg_backend_pid()", Integer.class)
                    .getSingleResult();
            return new Lease(session, key, backend);
        } catch (RuntimeException error) {
            try {
                if (acquired)
                    session.createNativeQuery("SELECT pg_advisory_unlock(:key)", Boolean.class)
                        .setParameter("key", key)
                        .getSingleResult();
            } finally {
                session.close();
            }
            throw error;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Session session;
        private final long key;
        private final int backend;
        private boolean closed;

        private Lease(Session session, long key, int backend) {
            this.session = session;
            this.key = key;
            this.backend = backend;
        }

        public synchronized boolean isValid() {
            return !closed
                && session.isOpen()
                && session.createNativeQuery("SELECT pg_backend_pid()", Integer.class)
                .getSingleResult()
                == backend;
        }

        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                session.createNativeQuery("SELECT pg_advisory_unlock(:key)", Boolean.class)
                    .setParameter("key", key)
                    .getSingleResult();
            } finally {
                session.close();
            }
        }
    }

    @Transactional
    public boolean resetRetryable(String team, String channel, long job, int candidate) {
        boolean acquired =
            entityManager
                .unwrap(Session.class)
                .createNativeQuery("SELECT pg_try_advisory_xact_lock(:key)", Boolean.class)
                .setParameter("key", key(team, channel))
                .getSingleResult();
        if (!acquired)
            throw new Problem(409, "slack_service_running", "Slack 서비스를 중지한 뒤 실패·전송불명 알림을 재설정하세요.");
        return notifications.deleteRetryable(team, channel, job, candidate) == 1;
    }
}
