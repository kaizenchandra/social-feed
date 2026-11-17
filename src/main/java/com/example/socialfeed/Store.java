package com.example.socialfeed;

import java.util.*;

import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.*;

@Repository
public class Store {
    // Every post retrieval, including cached sessions and media downloads, goes through this predicate.
    static final String VISIBLE = """
            p.state='PUBLISHED' AND u.active AND
            (p.author=:viewer OR (
            NOT EXISTS(select 1 from blocks b where (b.actor=:viewer and b.target=p.author) or (b.actor=p.author and b.target=:viewer))
            AND (NOT u.private OR EXISTS(select 1 from follows f where f.follower=:viewer and f.author=p.author))
            AND (p.visibility='PUBLIC' OR (p.visibility='FOLLOWERS' AND EXISTS(select 1 from follows f where f.follower=:viewer and f.author=p.author)))
            ))
            """;
    final JdbcClient db;

    public Store(JdbcClient db) {
        this.db = db;
    }

    JdbcClient client() {
        return db;
    }

    List<Map<String, Object>> rows(String sql, Object... args) {
        return db.sql(sql).params(args).query().listOfRows();
    }

    Map<String, Object> one(String sql, Object... args) {
        var r = rows(sql, args);
        if (r.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Resource not found");
        return r.getFirst();
    }

    int exec(String sql, Object... args) {
        return db.sql(sql).params(args).update();
    }

    long count(String sql, Object... args) {
        return db.sql(sql).params(args).query(Long.class).single();
    }

    void active(UUID actor) {
        if (count("select count(*) from users where id=? and active", actor) == 0)
            throw new ResponseStatusException(FORBIDDEN, "Active profile required");
    }

    void lockUsers(UUID a, UUID b) {
        rows("select id from users where id in (?,?) order by id for update", a, b);
        active(a);
    }

    List<Map<String, Object>> views(List<Map<String, Object>> posts) {
        if (posts.isEmpty()) return posts;
        var media = db.sql("select id,post,content_type,bytes from post_media where complete and post in (:ids)").param("ids", posts.stream().map(p -> p.get("id")).toList()).query().listOfRows();
        var result = new ArrayList<Map<String, Object>>();
        for (var p : posts) {
            var safe = new LinkedHashMap<String, Object>(p);
            safe.remove("request_key");
            safe.remove("request_hash");
            safe.remove("delivery");
            safe.put("media", media.stream().filter(m -> m.get("post").equals(p.get("id"))).toList());
            result.add(safe);
        }
        return result;
    }

    Map<String, Object> visible(UUID viewer, UUID id) {
        active(viewer);
        var result = db.sql("select p.*, (select count(*) from likes l where l.post=p.id) as likes, (select count(*) from comments c where c.post=p.id and not c.deleted) as comments from posts p join users u on u.id=p.author where p.id=:id AND " + VISIBLE)
                .param("viewer", viewer).param("id", id).query((rs, n) -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    var meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) m.put(meta.getColumnLabel(i), rs.getObject(i));
                    return m;
                }).optional().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Post not found"));
        return views(List.of(result)).getFirst();
    }
}
