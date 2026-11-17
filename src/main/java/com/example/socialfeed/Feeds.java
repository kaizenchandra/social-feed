package com.example.socialfeed;

import java.util.*;
import java.time.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;

import static org.springframework.http.HttpStatus.*;

@Service
public class Feeds {
    final Store s;
    final StringRedisTemplate redis;
    final MeterRegistry metrics;
    @org.springframework.beans.factory.annotation.Value("${feed.ranking.recency:0.75}")
    double recencyWeight;
    @org.springframework.beans.factory.annotation.Value("${feed.ranking.engagement:0.20}")
    double engagementWeight;
    @org.springframework.beans.factory.annotation.Value("${feed.ranking.self:0.05}")
    double selfWeight;

    public Feeds(Store s, StringRedisTemplate redis, MeterRegistry metrics) {
        this.s = s;
        this.redis = redis;
        this.metrics = metrics;
    }

    @jakarta.annotation.PostConstruct
    void validateWeights() {
        if (recencyWeight < 0 || engagementWeight < 0 || selfWeight < 0 || Math.abs(recencyWeight + engagementWeight + selfWeight - 1) > 0.00001)
            throw new IllegalArgumentException("Ranking weights must sum to one");
    }

    @Transactional
    public Contracts.Page home(UUID viewer, String mode, String cursor, int limit, UUID profile) {
        s.active(viewer);
        if (!Set.of("chronological", "ranked", "pull").contains(mode))
            throw new ResponseStatusException(BAD_REQUEST, "Unknown mode");
        limit = Math.clamp(limit, 1, 50);
        Cursor c = cursor == null ? new Cursor(snapshot(viewer, mode, profile), 0) : Cursor.decode(cursor);
        var session = s.one("select * from feed_sessions where id=? and viewer=? and expires_at>now()", c.session(), viewer);
        if (!java.util.Objects.equals(session.get("scope"), profile) || !session.get("mode").equals(mode))
            throw new ResponseStatusException(BAD_REQUEST, "Cursor mode mismatch");
        List<Map<String, Object>> ids;
        String cacheKey = "session:" + c.session();
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                ids = new ArrayList<>();
                int i = 0;
                for (String value : cached.split(",")) {
                    if (!value.isEmpty() && i >= c.position())
                        ids.add(Map.of("position", i, "post", UUID.fromString(value)));
                    i++;
                }
                metrics.counter("feed.cache", "result", "hit").increment();
            } else {
                var all = s.rows("select position,post from feed_session_items where session=? order by position", c.session());
                String values = String.join(",", all.stream().map(x -> x.get("post").toString()).toList());
                redis.opsForValue().set(cacheKey, values, Duration.ofMinutes(2));
                ids = all.stream().filter(x -> ((Number) x.get("position")).intValue() >= c.position()).toList();
                metrics.counter("feed.cache", "result", "miss").increment();
            }
        } catch (org.springframework.dao.DataAccessException e) {
            metrics.counter("feed.cache", "result", "error").increment();
            ids = s.rows("select position,post from feed_session_items where session=? and position>=? order by position limit 1000", c.session(), c.position());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int next = c.position();
        Map<UUID, Map<String, Object>> visible = new HashMap<>();
        if (!ids.isEmpty()) {
            String scope = profile == null ? " AND (p.author=:viewer OR EXISTS(select 1 from follows f where f.follower=:viewer and f.author=p.author))" : "";
            var rows = s.client().sql("select p.*,(select count(*) from likes l where l.post=p.id) likes,(select count(*) from comments c where c.post=p.id and not c.deleted) comments from posts p join users u on u.id=p.author where p.id in (:ids) AND " + Store.VISIBLE + scope + " AND NOT EXISTS(select 1 from mutes m where m.actor=:viewer and m.target=p.author)").param("viewer", viewer).param("ids", ids.stream().map(x -> x.get("post")).toList()).query().listOfRows();
            for (var row : rows) visible.put((UUID) row.get("id"), row);
        }
        for (var item : ids) {
            next = ((Number) item.get("position")).intValue() + 1;
            var p = visible.get((UUID) item.get("post"));
            if (p != null) out.add(p);
            else metrics.counter("feed.visibility.filtered").increment();
            if (out.size() == limit) break;
        }
        boolean more = s.count("select count(*) from feed_session_items where session=? and position>=?", c.session(), next) > 0;
        return new Contracts.Page(s.views(out), more ? new Cursor(c.session(), next).encode() : null);
    }

    UUID snapshot(UUID viewer, String mode, UUID profile) {
        String scope = profile != null ? "p.author=:profile" : "(p.author=:viewer OR EXISTS(select 1 from follows f where f.follower=:viewer and f.author=p.author))";
        // Bounded truth fallback closes follow backfill and mode-transition gaps; indexed author lookup required at scale.
        String sql = "select p.*, (select count(*) from likes l where l.post=p.id) likes,(select count(*) from comments c where c.post=p.id and not c.deleted) comments from posts p join users u on u.id=p.author where " + Store.VISIBLE + " AND " + scope + " AND NOT EXISTS(select 1 from mutes m where m.actor=:viewer and m.target=p.author) AND p.created_at<=:snapshot order by p.created_at desc,p.id desc limit 1000";
        Instant now = Instant.now();
        var q = s.client().sql(sql).param("viewer", viewer).param("snapshot", java.sql.Timestamp.from(now));
        if (profile != null) q = q.param("profile", profile);
        var candidates = new ArrayList<>(q.query().listOfRows());
        if (!mode.equals("pull") && profile == null) {
            var pushed = s.client().sql("select p.*,(select count(*) from likes l where l.post=p.id) likes,(select count(*) from comments c where c.post=p.id and not c.deleted) comments from feed_entries e join posts p on p.id=e.post join users u on u.id=p.author where e.viewer=:viewer and " + Store.VISIBLE + " AND " + scope + " AND NOT EXISTS(select 1 from mutes m where m.actor=:viewer and m.target=p.author) and p.created_at<=:snapshot order by p.created_at desc,p.id desc limit 1000").param("viewer", viewer).param("snapshot", java.sql.Timestamp.from(now)).query().listOfRows();
            var seen = new HashSet<UUID>();
            candidates.forEach(p -> seen.add((UUID) p.get("id")));
            for (var p : pushed) if (seen.add((UUID) p.get("id"))) candidates.add(p);
        }
        Comparator<Map<String, Object>> chronological = Comparator.<Map<String, Object>, Instant>comparing(p -> ((java.sql.Timestamp) p.get("created_at")).toInstant()).reversed().thenComparing(p -> p.get("id").toString(), Comparator.reverseOrder());
        Comparator<Map<String, Object>> ordering = mode.equals("ranked") ? Comparator.<Map<String, Object>>comparingDouble(p -> Ranking.score(((java.sql.Timestamp) p.get("created_at")).toInstant(), now, ((Number) p.get("likes")).longValue(), ((Number) p.get("comments")).longValue(), viewer.equals(p.get("author")), recencyWeight, engagementWeight, selfWeight)).reversed().thenComparing(chronological) : chronological;
        candidates.sort(ordering);
        UUID id = UUID.randomUUID();
        s.exec("insert into feed_sessions values(?,?,?,1,?,?)", id, viewer, mode, profile, java.sql.Timestamp.from(now.plusSeconds(900)));
        int pos = 0;
        for (var p : candidates.stream().limit(1000).toList())
            s.exec("insert into feed_session_items values(?,?,?)", id, pos++, p.get("id"));
        return id;
    }

    public record Cursor(UUID session, int position) {
        public static Cursor decode(String raw) {
            if (raw.length() > 200) throw new IllegalArgumentException();
            String[] x = new String(Base64.getUrlDecoder().decode(raw), java.nio.charset.StandardCharsets.UTF_8).split(":", -1);
            if (x.length != 2 || !x[1].matches("[0-9]{1,4}")) throw new IllegalArgumentException();
            int pos = Integer.parseInt(x[1]);
            if (pos > 1000) throw new IllegalArgumentException();
            return new Cursor(UUID.fromString(x[0]), pos);
        }

        public String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString((session + ":" + position).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
