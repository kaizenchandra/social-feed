package com.example.socialfeed;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"feed.jwt-secret=integration-secret-at-least-32-characters", "feed.workers-enabled=false", "feed.celebrity-threshold=2"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class SystemIntegrationTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");
    @Container
    static GenericContainer<?> s3 = new GenericContainer<>("localstack/localstack:4.10.0").withEnv("SERVICES", "s3").withExposedPorts(4566);
    @Autowired
    SocialGraph graph;
    @Autowired
    Posts posts;
    @Autowired
    Feeds feeds;
    @Autowired
    Store store;
    @Autowired
    Media media;
    @Autowired
    javax.sql.DataSource dataSource;
    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("feed.s3-endpoint", () -> "http://" + s3.getHost() + ":" + s3.getMappedPort(4566));
    }

    UUID user(boolean priv) {
        UUID id = UUID.randomUUID();
        graph.profile(id, new Contracts.Profile("Test", priv, true));
        return id;
    }

    Map<String, Object> post(UUID a, String key) {
        return posts.create(a, key, new Contracts.CreatePost("hello", Contracts.Visibility.PUBLIC, Contracts.State.PUBLISHED, List.of()));
    }

    @Test
    void privacyIdempotencyAndPagination() {
        UUID a = user(false), b = user(true);
        graph.relation(a, b, "follow");
        UUID p = (UUID) post(b, "one").get("id");
        assertThatThrownBy(() -> store.visible(a, p)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        graph.relation(b, a, "accept");
        assertThat(store.visible(a, p)).containsEntry("id", p);
        assertThat(post(b, "one")).containsEntry("id", p);
        assertThat(store.count("select count(*) from outbox_events where entity=?", p)).isEqualTo(1);
        UUID p2 = (UUID) post(b, "two").get("id");
        var page = feeds.home(a, "ranked", null, 1, null);
        assertThat(page.items()).hasSize(1);
        UUID newer = (UUID) post(b, "three").get("id");
        var page2 = feeds.home(a, "ranked", page.nextCursor(), 10, null);
        assertThat(page2.items()).hasSize(1).noneMatch(x -> x.get("id").equals(newer) || x.get("id").equals(page.items().getFirst().get("id")));
        graph.relation(a, b, "block");
        assertThat(feeds.home(a, "ranked", page.nextCursor(), 10, null).items()).isEmpty();
        assertThatThrownBy(() -> store.visible(a, p2)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void concurrentLikesAndFollows() throws Exception {
        UUID a = user(false), b = user(false);
        UUID p = (UUID) post(b, "likes").get("id");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 16; i++)
                tasks.add(() -> {
                    graph.relation(a, b, "follow");
                    posts.like(a, p, true);
                    return null;
                });
            for (var f : pool.invokeAll(tasks)) f.get();
        }
        assertThat(store.count("select count(*) from likes where post=?", p)).isEqualTo(1);
        assertThat(store.count("select count(*) from follows where follower=? and author=?", a, b)).isEqualTo(1);
        posts.like(a, p, false);
        posts.like(a, p, false);
        assertThat(store.count("select count(*) from likes where post=?", p)).isZero();
    }

    @Test
    void fanoutDuplicateAndRestart() {
        UUID a = user(false), b = user(false);
        graph.relation(a, b, "follow");
        UUID p = (UUID) post(b, "fanout").get("id");
        var template = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(pgDataSource()));
        var workers = new EventWorkers(store, new org.springframework.kafka.core.KafkaTemplate<>(new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers", kafka.getBootstrapServers(), "key.serializer", org.apache.kafka.common.serialization.StringSerializer.class, "value.serializer", org.apache.kafka.common.serialization.StringSerializer.class))), template, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        String event = store.one("select id from outbox_events where entity=?", p).get("id").toString();
        workers.consume(event);
        workers.consume(event);
        workers.fanout();
        workers.fanout();
        assertThat(store.count("select count(*) from feed_entries where viewer=? and post=?", a, p)).isEqualTo(1);
    }

    javax.sql.DataSource pgDataSource() {
        return dataSource;
    }

    void ensureBucket() {
        try {
            media.client().createBucket(b -> b.bucket(media.bucket()));
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() != 409) throw e;
        }
    }

    void putMedia(String key, String type, byte[] bytes) {
        media.client().putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder().bucket(media.bucket()).key(key).contentType(type).build(), software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
    }

    EventWorkers worker(org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate) {
        return new EventWorkers(store, kafkaTemplate, new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    UUID pendingEvent() {
        UUID post = (UUID) post(user(false), "leased").get("id");
        UUID id = (UUID) store.one("select id from outbox_events where entity=?", post).get("id");
        store.exec("update outbox_events set created_at=(select min(created_at)-interval '1 day' from outbox_events) where id=?", id);
        return id;
    }

    @Test
    void mediaOwnershipAndImmutableCompletion() throws Exception {
        ensureBucket();
        UUID a = user(false), b = user(false);
        byte[] bytes = SecurityBoundariesTest.image("png");
        var upload = media.initiate(a, new Contracts.Upload("image/png", bytes.length));
        UUID id = (UUID) upload.get("uploadId");
        String staging = (String) store.one("select object_key from post_media where id=?", id).get("object_key");
        putMedia(staging, "image/png", bytes);
        media.complete(a, id);
        String frozen = (String) store.one("select object_key from post_media where id=?", id).get("object_key");
        putMedia(staging, "image/png", new byte[]{1, 2, 3});
        media.complete(a, id);
        assertThat(media.client().getObjectAsBytes(software.amazon.awssdk.services.s3.model.GetObjectRequest.builder().bucket(media.bucket()).key(frozen).build()).asByteArray()).isEqualTo(bytes);
        assertThat(media.download(a, id).get("url")).contains("objects/");
        assertThatThrownBy(() -> media.download(b, id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void deletionAndOptimisticLock() {
        UUID a = user(false);
        var p = post(a, "delete");
        UUID id = (UUID) p.get("id");
        posts.edit(a, id, new Contracts.EditPost("changed", Contracts.Visibility.PUBLIC, Contracts.State.PUBLISHED, 0));
        assertThatThrownBy(() -> posts.edit(a, id, new Contracts.EditPost("stale", Contracts.Visibility.PUBLIC, Contracts.State.PUBLISHED, 0))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        posts.delete(a, id);
        assertThatThrownBy(() -> store.visible(a, id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void authenticatedHttpPrivacy() throws Exception {
        UUID a = user(false), b = user(true);
        UUID p = (UUID) post(b, "http").get("id");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/posts/" + p)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/posts/" + p).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(a.toString())))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test
    void redisFailureUsesTruth() {
        UUID a = user(false);
        post(a, "redis");
        var broken = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(broken.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("injected outage"));
        var f = new Feeds(store, broken, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThat(f.home(a, "chronological", null, 20, null).items()).hasSize(1);
    }

    @Test
    void partialBatchRollbackAndResume() {
        UUID author = user(false);
        UUID p = (UUID) post(author, "batch").get("id");
        store.exec("insert into users(id,display_name) select gen_random_uuid(),'bulk' from generate_series(1,501)");
        store.exec("insert into follows(follower,author) select id,? from users where display_name='bulk'", author);
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var k = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        var worker = new EventWorkers(store, k, tx, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        String event = store.one("select id from outbox_events where entity=?", p).get("id").toString();
        worker.consume(event);
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> {
            worker.fanout();
            throw new IllegalStateException("crash");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.count("select count(*) from feed_entries where post=?", p)).isZero();
        worker.fanout();
        assertThat(store.count("select count(*) from feed_entries where post=?", p)).isEqualTo(500);
        worker.fanout();
        worker.consume(event);
        worker.fanout();
        assertThat(store.count("select count(*) from feed_entries where post=?", p)).isEqualTo(501);
    }

    @Test
    void brokerPublicationAndDuplicateReplay() throws Exception {
        UUID a = user(false);
        UUID p = (UUID) post(a, "broker").get("id");
        var properties = Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers(), "key.serializer", org.apache.kafka.common.serialization.StringSerializer.class, "value.serializer", org.apache.kafka.common.serialization.StringSerializer.class);
        var factory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(properties);
        var template = new org.springframework.kafka.core.KafkaTemplate<String, String>(factory);
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var worker = new EventWorkers(store, template, tx, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        for (int i = 0; i < 100 && store.count("select count(*) from outbox_events where entity=? and published", p) == 0; i++)
            worker.publish();
        assertThat(store.count("select count(*) from outbox_events where entity=? and published", p)).isEqualTo(1);
        factory.destroy();
    }

    @Test
    void imageThumbnailGeneratedAndPrivate() throws Exception {
        try {
            media.client().createBucket(b -> b.bucket(media.bucket()));
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() != 409) throw e;
        }
        UUID actor = user(false);
        var image = new java.awt.image.BufferedImage(640, 320, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        var upload = media.initiate(actor, new Contracts.Upload("image/png", bytes.size()));
        UUID id = (UUID) upload.get("uploadId");
        var m = store.one("select object_key from post_media where id=?", id);
        media.client().putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder().bucket(media.bucket()).key((String) m.get("object_key")).contentType("image/png").build(), software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes.toByteArray()));
        media.complete(actor, id);
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var processor = new MediaProcessor(store, media, tx, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        for (int i = 0; i < 5; i++) processor.thumbnails();
        assertThat(media.download(actor, id, true).get("url")).contains("thumbnails/");
    }

    @Test
    void celebrityUsesPullAndIsVisible() {
        UUID a = user(false), b = user(false), c = user(false);
        graph.relation(a, c, "follow");
        graph.relation(b, c, "follow");
        var p = post(c, "celebrity");
        assertThat(p).containsEntry("delivery", "PULL");
        assertThat(feeds.home(a, "chronological", null, 20, null).items()).anyMatch(x -> x.get("id").equals(p.get("id")));
        assertThat(store.count("select count(*) from feed_entries where post=?", p.get("id"))).isZero();
    }

    @Test
    void privacyTransitionInvalidatesOldSession() {
        UUID a = user(false), b = user(false);
        graph.relation(a, b, "follow");
        post(b, "privacy1");
        post(b, "privacy2");
        var page = feeds.home(a, "chronological", null, 1, null);
        graph.relation(a, b, "unfollow");
        graph.profile(b, new Contracts.Profile("private", true, true));
        assertThat(feeds.home(a, "chronological", page.nextCursor(), 20, null).items()).isEmpty();
    }

    @Test
    void timestampTiesAndCursorExpiry() {
        UUID a = user(false);
        for (int i = 0; i < 5; i++) post(a, "tie" + i);
        store.exec("update posts set created_at='2020-01-01' where author=?", a);
        var seen = new HashSet<Object>();
        String cursor = null;
        do {
            var page = feeds.home(a, "chronological", cursor, 1, null);
            for (var p : page.items()) assertThat(seen.add(p.get("id"))).isTrue();
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).hasSize(5);
        var session = feeds.home(a, "chronological", null, 1, null);
        UUID sessionId = Feeds.Cursor.decode(session.nextCursor()).session();
        store.exec("update feed_sessions set expires_at=now()-interval '1 minute' where id=?", sessionId);
        assertThatThrownBy(() -> feeds.home(a, "chronological", session.nextCursor(), 1, null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void brokerOutageLeavesRetryableOutbox() {
        UUID actor = user(false);
        UUID p = (UUID) post(actor, "outage").get("id");
        store.exec("update outbox_events set created_at='2000-01-01' where entity=?", p);
        var k = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(k.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new RuntimeException("broker down")));
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        new EventWorkers(store, k, tx, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).publish();
        var e = store.one("select published,attempts,dead from outbox_events where entity=?", p);
        assertThat(e).containsEntry("published", false).containsEntry("attempts", 1).containsEntry("dead", false);
    }

    @Test
    void stalePushEntriesCannotConsumeSnapshotSlots() {
        UUID viewer = user(false), unfollowed = user(false), muted = user(false), author = user(false);
        for (UUID id : List.of(unfollowed, muted, author)) graph.relation(viewer, id, "follow");
        UUID stale = (UUID) post(unfollowed, "stale").get("id"), silenced = (UUID) post(muted, "muted").get("id");
        for (UUID id : List.of(stale, silenced)) store.exec("insert into feed_entries values(?,?,now())", viewer, id);
        graph.relation(viewer, unfollowed, "unfollow");
        graph.relation(viewer, muted, "mute");
        UUID eligible = (UUID) post(author, "eligible").get("id");
        var page = feeds.home(viewer, "ranked", null, 20, null);
        assertThat(page.items()).extracting(p -> p.get("id")).containsExactly(eligible);
        assertThat(store.rows("select i.post from feed_session_items i join feed_sessions s on s.id=i.session where s.viewer=?", viewer))
                .extracting(p -> p.get("post")).containsExactly(eligible);
    }

    @Test
    void repeatedPrivateFollowDoesNotReopenApprovedRequest() {
        UUID follower = user(false), author = user(true);
        graph.relation(follower, author, "follow");
        graph.relation(author, follower, "accept");
        graph.relation(follower, author, "follow");
        graph.relation(follower, author, "follow");
        assertThat(store.count("select count(*) from follows where follower=? and author=?", follower, author)).isEqualTo(1);
        assertThat(graph.list(author, author, "requests", null, 20)).isEmpty();
    }

    @Test
    void publicFollowClearsObsoletePrivateRequest() {
        UUID follower = user(false), author = user(true);
        graph.relation(follower, author, "follow");
        graph.profile(author, new Contracts.Profile("public now", false, true));
        graph.relation(follower, author, "follow");
        assertThat(store.count("select count(*) from follows where follower=? and author=?", follower, author)).isEqualTo(1);
        assertThat(graph.list(author, author, "requests", null, 20)).isEmpty();
    }

    @Test
    void unavailableMediaRollsBackPostAndOutbox() {
        UUID actor = user(false);
        long before = store.count("select count(*) from outbox_events");
        assertThatThrownBy(() -> posts.create(actor, "failed-media", new Contracts.CreatePost("invalid attachment", Contracts.Visibility.PUBLIC, Contracts.State.PUBLISHED, List.of(UUID.randomUUID()))))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.count("select count(*) from posts where author=?", actor)).isZero();
        assertThat(store.count("select count(*) from outbox_events")).isEqualTo(before);
        assertThat(post(actor, "failed-media")).containsEntry("caption", "hello");
    }

    @Test
    void outboxSendHoldsNoDatabaseTransaction() {
        UUID event = pendingEvent();
        var kafkaTemplate = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(kafkaTemplate.send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("select id from outbox_events where id=? for update nowait")) {
                statement.setObject(1, event);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                }
            }
            return CompletableFuture.completedFuture(null);
        });
        worker(kafkaTemplate).publish();
        assertThat(store.one("select published,attempts,claim_token,claimed_until from outbox_events where id=?", event))
                .containsEntry("published", true).containsEntry("attempts", 1).containsEntry("claim_token", null).containsEntry("claimed_until", null);
    }

    @Test
    void crashedPublisherLeaseExpiresAndFencesOldCheckpoint() {
        UUID event = pendingEvent();
        var worker = worker(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class));
        var old = worker.claimPublication();
        assertThat(old.event()).isEqualTo(event);
        store.exec("update outbox_events set claimed_until=now()-interval '1 second' where id=?", event);
        assertThat(worker.finishPublication(old, true)).isFalse();
        var replacement = worker.claimPublication();
        assertThat(replacement.event()).isEqualTo(event);
        assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(worker.finishPublication(old, false)).isFalse();
        assertThat(worker.finishPublication(replacement, true)).isTrue();
        assertThat(store.one("select published,attempts from outbox_events where id=?", event)).containsEntry("published", true).containsEntry("attempts", 2);
    }

    @Test
    void crashedAttemptsAreBoundedAndBecomeDead() {
        UUID event = pendingEvent();
        var worker = worker(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class));
        store.exec("update outbox_events set attempts=9 where id=?", event);
        var last = worker.claimPublication();
        assertThat(last.event()).isEqualTo(event);
        store.exec("update outbox_events set claimed_until=now()-interval '1 second' where id=?", event);
        var next = worker.claimPublication();
        if (next != null) worker.finishPublication(next, true);
        assertThat(store.one("select published,attempts,dead,claim_token from outbox_events where id=?", event))
                .containsEntry("published", false).containsEntry("attempts", 10).containsEntry("dead", true).containsEntry("claim_token", null);
    }

    @Test
    void concurrentPublishersCannotClaimTheSameUnexpiredLease() throws Exception {
        UUID event = pendingEvent();
        var worker = worker(org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class));
        var claims = new ArrayList<EventWorkers.Publication>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<EventWorkers.Publication>>();
            for (int i = 0; i < 4; i++) tasks.add(worker::claimPublication);
            for (var result : pool.invokeAll(tasks)) {
                var claim = result.get();
                if (claim != null) claims.add(claim);
            }
        }
        assertThat(claims.stream().filter(c -> c.event().equals(event)).count()).isEqualTo(1);
        for (var claim : claims) assertThat(worker.finishPublication(claim, true)).isTrue();
    }

    @Test
    void forgedImageCannotBeCompletedDownloadedOrPublished() {
        ensureBucket();
        UUID actor = user(false);
        byte[] bytes = "<html>forged image</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var upload = media.initiate(actor, new Contracts.Upload("image/png", bytes.length));
        UUID id = (UUID) upload.get("uploadId");
        putMedia((String) store.one("select object_key from post_media where id=?", id).get("object_key"), "image/png", bytes);
        assertThatThrownBy(() -> media.complete(actor, id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.one("select complete,object_key from post_media where id=?", id)).containsEntry("complete", false);
        assertThatThrownBy(() -> media.download(actor, id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> posts.create(actor, "forged", new Contracts.CreatePost("forged", Contracts.Visibility.PUBLIC, Contracts.State.PUBLISHED, List.of(id)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.count("select count(*) from posts where author=?", actor)).isZero();
    }

    @Test
    void oversizedImageRejectedBeforeAllocatingUpload() {
        UUID actor = user(false);
        assertThatThrownBy(() -> media.initiate(actor, new Contracts.Upload("image/png", MediaValidation.IMAGE_BYTES + 1)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.count("select count(*) from post_media where owner=?", actor)).isZero();
    }

    @Test
    void mediaValidationOverloadIsRejectedWithoutCompletingAndCanRetry() throws Exception {
        ensureBucket();
        UUID actor = user(false);
        byte[] bytes = SecurityBoundariesTest.image("png");
        UUID id = (UUID) media.initiate(actor, new Contracts.Upload("image/png", bytes.length)).get("uploadId");
        putMedia((String) store.one("select object_key from post_media where id=?", id).get("object_key"), "image/png", bytes);
        Media target = org.springframework.test.util.AopTestUtils.getTargetObject(media);
        target.validationSlots.acquire(2);
        try {
            assertThatThrownBy(() -> media.complete(actor, id)).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
            assertThat(store.one("select complete from post_media where id=?", id)).containsEntry("complete", false);
        } finally {
            target.validationSlots.release(2);
        }
        media.complete(actor, id);
        assertThat(media.download(actor, id).get("url")).contains("objects/");
    }

    @Test
    void oversizedJsonCannotMutateProfile() throws Exception {
        UUID actor = user(false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/me")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(actor.toString())))
                        .contentType("application/json").content(" ".repeat(RequestBodyLimits.MAX_BYTES + 1)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPayloadTooLarge());
        assertThat(graph.profile(actor, actor)).containsEntry("version", 0L);
    }

    @Test
    void malformedEventIsDurablySentToConfiguredDeadLetterTopic() throws Exception {
        var factory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(Map.of("bootstrap.servers", kafka.getBootstrapServers(), "key.serializer", org.apache.kafka.common.serialization.StringSerializer.class, "value.serializer", org.apache.kafka.common.serialization.StringSerializer.class));
        var template = new org.springframework.kafka.core.KafkaTemplate<String, String>(factory);
        String malformed = "invalid-" + UUID.randomUUID();
        try {
            var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>("feed.events", 0, 99L, "source-key", malformed);
            var recoverer = worker(template).deadLetterRecoverer(template);
            recoverer.accept(record, null, new IllegalArgumentException("Malformed UUID"));
            var properties = new Properties();
            properties.put("bootstrap.servers", kafka.getBootstrapServers());
            properties.put("group.id", "dlt-test-" + UUID.randomUUID());
            properties.put("auto.offset.reset", "earliest");
            properties.put("enable.auto.commit", "false");
            try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(properties, new org.apache.kafka.common.serialization.StringDeserializer(), new org.apache.kafka.common.serialization.StringDeserializer())) {
                consumer.subscribe(List.of("feed.events.DLT"));
                boolean found = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!found && System.nanoTime() < deadline)
                    for (var item : consumer.poll(java.time.Duration.ofMillis(200)))
                        if (malformed.equals(item.value())) {
                            assertThat(item.key()).isEqualTo("source-key");
                            assertThat(item.headers().lastHeader(org.springframework.kafka.support.KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();
                            found = true;
                        }
                assertThat(found).isTrue();
            }
        } finally {
            factory.destroy();
        }
    }
}
