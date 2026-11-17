package com.example.socialfeed;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.*;

@Service
public class Posts {
    final Store s;
    final int threshold;

    public Posts(Store s, @Value("${feed.celebrity-threshold}") int threshold) {
        this.s = s;
        this.threshold = threshold;
    }

    @Transactional
    public Map<String, Object> create(UUID actor, String key, Contracts.CreatePost p) {
        s.active(actor);
        if (key == null || key.isBlank() || key.length() > 100)
            throw new ResponseStatusException(BAD_REQUEST, "Idempotency-Key required (1..100)");
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        UUID id = UUID.randomUUID();
        if (p.state() != Contracts.State.DRAFT && p.state() != Contracts.State.PUBLISHED)
            throw new ResponseStatusException(BAD_REQUEST, "Create DRAFT or PUBLISHED");
        boolean push = s.count("select count(*) from (select 1 from follows where author=? limit ?) eligible", actor, threshold) < threshold;
        int inserted = s.exec("insert into posts(id,author,caption,visibility,state,delivery,request_key,request_hash) values(?,?,?,?,?,?,?,?) on conflict(author,request_key) do nothing", id, actor, p.caption(), p.visibility().name(), p.state().name(), push ? "PUSH" : "PULL", key, hash);
        var result = s.one("select * from posts where author=? and request_key=?", actor, key);
        if (!result.get("request_hash").equals(hash))
            throw new ResponseStatusException(CONFLICT, "Idempotency key reused with different request");
        if (inserted == 0) return result;
        if (p.media() != null) for (UUID media : p.media())
            if (s.exec("update post_media set post=? where id=? and owner=? and complete and post is null", id, media, actor) != 1)
                throw new ResponseStatusException(CONFLICT, "Media unavailable");
        event(id, 0, "PostCreated");
        return result;
    }

    @Transactional
    public Map<String, Object> edit(UUID actor, UUID id, Contracts.EditPost p) {
        s.active(actor);
        var old = s.one("select * from posts where id=? and author=? for update", id, actor);
        if (!Contracts.transition(Contracts.State.valueOf((String) old.get("state")), p.state()))
            throw new ResponseStatusException(CONFLICT, "Invalid state transition");
        if (s.exec("update posts set caption=?,visibility=?,state=?,version=version+1 where id=? and author=? and version=?", p.caption(), p.visibility().name(), p.state().name(), id, actor, p.version()) != 1)
            throw new ResponseStatusException(CONFLICT, "Stale version");
        event(id, p.version() + 1, "PostUpdated");
        return s.one("select * from posts where id=?", id);
    }

    @Transactional
    public void delete(UUID actor, UUID id) {
        s.active(actor);
        var p = s.one("update posts set state='DELETED',version=version+1 where id=? and author=? returning version", id, actor);
        event(id, ((Number) p.get("version")).longValue(), "PostDeleted");
    }

    void event(UUID id, long v, String type) {
        s.exec("insert into outbox_events(id,entity,entity_version,event_type,correlation_id) values(?,?,?,?,?)", UUID.randomUUID(), id, v, type, UUID.randomUUID());
    }

    @Transactional
    public void like(UUID actor, UUID id, boolean on) {
        s.visible(actor, id);
        // Serialize mutations on this post; no eventually aggregated counter to drift.
        s.rows("select id from posts where id=? for update", id);
        if (on) s.exec("insert into likes values(?,?) on conflict do nothing", id, actor);
        else s.exec("delete from likes where post=? and actor=?", id, actor);
    }

    @Transactional
    public Map<String, Object> comment(UUID actor, UUID post, Contracts.Comment c) {
        s.visible(actor, post);
        return s.one("insert into comments(id,post,actor,body) values(?,?,?,?) returning *", UUID.randomUUID(), post, actor, c.body());
    }

    @Transactional
    public void deleteComment(UUID actor, UUID id) {
        s.active(actor);
        s.exec("update comments set deleted=true where id=? and (actor=? or post in(select id from posts where author=?))", id, actor, actor);
    }
}
