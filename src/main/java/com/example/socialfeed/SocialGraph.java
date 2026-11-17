package com.example.socialfeed;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.*;

@Service
public class SocialGraph {
    final Store s;

    public SocialGraph(Store s) {
        this.s = s;
    }

    @Transactional
    public Map<String, Object> profile(UUID actor, Contracts.Profile p) {
        s.exec("insert into users(id,display_name,private,active) values(?,?,?,?) on conflict(id) do update set display_name=excluded.display_name, private=excluded.private,active=excluded.active, version=users.version+1", actor, p.displayName(), p.privateAccount(), p.active());
        return profile(actor, actor);
    }

    public Map<String, Object> profile(UUID actor, UUID id) {
        if (s.count("select count(*) from blocks where (actor=? and target=?) or (actor=? and target=?)", actor, id, id, actor) > 0)
            throw new ResponseStatusException(NOT_FOUND);
        return s.one("select id,display_name,private,active,version,(select count(*) from follows where author=u.id) followers,(select count(*) from follows where follower=u.id) following from users u where id=?", id);
    }

    @Transactional
    public void relation(UUID actor, UUID target, String action) {
        if (actor.equals(target)) throw new ResponseStatusException(BAD_REQUEST, "Self relationship prohibited");
        s.lockUsers(actor, target);
        s.one("select id from users where id=? and active", target);
        switch (action) {
            case "follow" -> {
                if (s.count("select count(*) from blocks where (actor=? and target=?) or (actor=? and target=?)", actor, target, target, actor) > 0)
                    throw new ResponseStatusException(FORBIDDEN);
                boolean priv = (boolean) s.one("select private from users where id=?", target).get("private");
                if (priv) {
                    if (s.count("select count(*) from follows where follower=? and author=?", actor, target) == 0)
                        s.exec("insert into follow_requests values(?,?) on conflict do nothing", actor, target);
                } else {
                    s.exec("insert into follows(follower,author) values(?,?) on conflict do nothing", actor, target);
                    s.exec("delete from follow_requests where follower=? and author=?", actor, target);
                }
            }
            case "unfollow" -> {
                s.exec("delete from follows where follower=? and author=?", actor, target);
                s.exec("delete from follow_requests where follower=? and author=?", actor, target);
            }
            case "block" -> {
                s.exec("insert into blocks values(?,?) on conflict do nothing", actor, target);
                s.exec("delete from follows where (follower=? and author=?) or (follower=? and author=?)", actor, target, target, actor);
                s.exec("delete from follow_requests where (follower=? and author=?) or (follower=? and author=?)", actor, target, target, actor);
            }
            case "unblock" -> s.exec("delete from blocks where actor=? and target=?", actor, target);
            case "mute" -> s.exec("insert into mutes values(?,?) on conflict do nothing", actor, target);
            case "unmute" -> s.exec("delete from mutes where actor=? and target=?", actor, target);
            case "accept", "reject" -> {
                if (s.exec("delete from follow_requests where follower=? and author=?", target, actor) > 0 && action.equals("accept"))
                    s.exec("insert into follows(follower,author) values(?,?) on conflict do nothing", target, actor);
            }
            default -> throw new ResponseStatusException(BAD_REQUEST);
        }
    }

    public List<Map<String, Object>> list(UUID actor, UUID id, String type, UUID after, int limit) {
        s.active(actor);
        profile(actor, id);
        if (!actor.equals(id) && (boolean) profile(actor, id).get("private"))
            throw new ResponseStatusException(FORBIDDEN, "Private graph");
        String sql = switch (type) {
            case "followers" ->
                    "select follower as id from follows where author=? and follower>? order by follower limit ?";
            case "following" ->
                    "select author as id from follows where follower=? and author>? order by author limit ?";
            case "requests" -> {
                if (!actor.equals(id)) throw new ResponseStatusException(FORBIDDEN);
                yield "select follower as id from follow_requests where author=? and follower>? order by follower limit ?";
            }
            default -> throw new ResponseStatusException(BAD_REQUEST);
        };
        return s.rows(sql, id, after == null ? new UUID(0, 0) : after, Math.clamp(limit, 1, 50));
    }
}
