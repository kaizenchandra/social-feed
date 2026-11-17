package com.example.socialfeed;

import java.util.*;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.util.backoff.FixedBackOff;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "feed.workers-enabled", havingValue = "true", matchIfMissing = true)
public class EventWorkers {
    final java.util.concurrent.atomic.AtomicLong outboxBacklog = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong jobBacklog = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong deadOutbox = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong leasedOutbox = new java.util.concurrent.atomic.AtomicLong();
    final Store s;
    final KafkaTemplate<String, String> kafka;
    final TransactionTemplate tx;
    final TransactionTemplate publicationTx;
    final MeterRegistry metrics;

    public EventWorkers(Store s, KafkaTemplate<String, String> kafka, TransactionTemplate tx, MeterRegistry metrics) {
        this.s = s;
        this.kafka = kafka;
        this.tx = tx;
        this.metrics = metrics;
        publicationTx = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        publicationTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        metrics.gauge("feed.outbox.backlog", outboxBacklog);
        metrics.gauge("feed.fanout.backlog", jobBacklog);
        metrics.gauge("feed.outbox.dead", deadOutbox);
        metrics.gauge("feed.outbox.leased", leasedOutbox);
    }

    @Bean
    NewTopic events() {
        return TopicBuilder.name("feed.events").partitions(12).replicas(1).build();
    }

    @Bean
    NewTopic deadLetters() {
        return TopicBuilder.name("feed.events.DLT").partitions(12).replicas(1).build();
    }

    @Bean
    DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, String> kt) {
        var recoverer = new DeadLetterPublishingRecoverer(kt, (record, error) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(6));
        return recoverer;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(ConsumerFactory<String, String> cf, DeadLetterPublishingRecoverer recoverer) {
        var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
        f.setConsumerFactory(cf);
        f.setConcurrency(2);
        f.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(500, 3)));
        return f;
    }

    // Claim and checkpoint are short independent transactions; Kafka never holds a row lock or connection.
    Publication claimPublication() {
        return publicationTx.execute(t -> {
            s.exec("with exhausted as (select id from outbox_events where not published and not dead and attempts>=10 and (claimed_until is null or claimed_until<=now()) for update skip locked limit 100) update outbox_events e set dead=true,claim_token=null,claimed_until=null from exhausted x where e.id=x.id");
            UUID token = UUID.randomUUID();
            var rows = s.rows("with candidate as (select id from outbox_events where not published and not dead and attempts<10 and next_attempt<=now() and (claimed_until is null or claimed_until<=now()) order by created_at,id for update skip locked limit 1) update outbox_events e set claim_token=?,claimed_until=now()+interval '30 seconds',attempts=attempts+1 from candidate c where e.id=c.id returning e.id,e.entity", token);
            if (rows.isEmpty()) return null;
            var row = rows.getFirst();
            return new Publication((UUID) row.get("id"), (UUID) row.get("entity"), token);
        });
    }

    boolean finishPublication(Publication claim, boolean success) {
        return Boolean.TRUE.equals(publicationTx.execute(t -> {
            int changed = s.exec(success ?
                    "update outbox_events set published=true,claim_token=null,claimed_until=null where id=? and claim_token=? and claimed_until>now() and not published" :
                    "update outbox_events set dead=attempts>=10,next_attempt=now()+make_interval(secs=>least(300,power(2,attempts)::int)),claim_token=null,claimed_until=null where id=? and claim_token=? and claimed_until>now() and not published", claim.event(), claim.token());
            return changed == 1;
        }));
    }

    @Scheduled(fixedDelay = 100)
    public void publish() {
        Publication claim = claimPublication();
        if (claim == null) return;
        boolean success = false;
        try {
            kafka.send("feed.events", claim.entity().toString(), claim.event().toString()).get(6, TimeUnit.SECONDS);
            success = true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        if (finishPublication(claim, success))
            metrics.counter(success ? "feed.outbox.published" : "feed.outbox.failures").increment();
    }

    @KafkaListener(topics = "feed.events")
    public void consume(String raw) {
        UUID event = UUID.fromString(raw);
        tx.executeWithoutResult(t -> {
            var e = s.one("select * from outbox_events where id=?", event);
            if (((Number) e.get("schema_version")).intValue() != 1)
                throw new IllegalArgumentException("Unsupported schema");
            s.exec("insert into feed_jobs(post) select id from posts where id=? and state='PUBLISHED' and delivery='PUSH' on conflict do nothing", e.get("entity"));
        });
    }

    @Scheduled(fixedDelay = 100)
    public void fanout() {
        tx.executeWithoutResult(t -> {
            var jobs = s.rows("select j.*,p.author,p.created_at from feed_jobs j join posts p on p.id=j.post where not j.done order by j.updated_at for update of j skip locked limit 1");
            if (jobs.isEmpty()) return;
            var j = jobs.getFirst();
            var followers = s.rows("select follower from follows where author=? and follower>? order by follower limit 500", j.get("author"), j.get("after_follower") == null ? new UUID(0, 0) : j.get("after_follower"));
            for (var f : followers)
                s.exec("insert into feed_entries values(?,?,?) on conflict do nothing", f.get("follower"), j.get("post"), j.get("created_at"));
            s.exec("update feed_jobs set after_follower=?,done=?,updated_at=now() where post=?", followers.isEmpty() ? j.get("after_follower") : followers.getLast().get("follower"), followers.size() < 500, j.get("post"));
            metrics.counter("feed.fanout.entries").increment(followers.size());
        });
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        tx.executeWithoutResult(t -> {
            s.exec("delete from feed_sessions where id in(select id from feed_sessions where expires_at<now() limit 1000)");
            s.exec("delete from feed_entries where (viewer,post) in(select viewer,post from feed_entries where created_at<now()-interval '7 days' limit 5000)");
        });
        jobBacklog.set(s.count("select count(*) from feed_jobs where not done"));
        outboxBacklog.set(s.count("select count(*) from outbox_events where not published and not dead"));
        deadOutbox.set(s.count("select count(*) from outbox_events where not published and dead"));
        leasedOutbox.set(s.count("select count(*) from outbox_events where not published and not dead and claimed_until>now()"));
    }

    record Publication(UUID event, UUID entity, UUID token) {
    }
}
