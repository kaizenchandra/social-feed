package com.example.socialfeed;

import java.util.*;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/v1")
public class Api {
    final SocialGraph graph;
    final Posts posts;
    final Feeds feeds;
    final Store s;
    final Media media;

    public Api(SocialGraph g, Posts p, Feeds f, Store s, Media m) {
        graph = g;
        posts = p;
        feeds = f;
        this.s = s;
        media = m;
    }

    UUID actor(Jwt j) {
        return UUID.fromString(j.getSubject());
    }

    @PatchMapping("/users/me")
    Map<String, Object> profile(@AuthenticationPrincipal Jwt j, @Valid @RequestBody Contracts.Profile p) {
        return graph.profile(actor(j), p);
    }

    @GetMapping("/users/{id}")
    Map<String, Object> profile(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return graph.profile(actor(j), id);
    }

    @PostMapping("/users/{id}/{action:follow|block|mute|accept|reject}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void relation(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @PathVariable String action) {
        graph.relation(actor(j), id, action);
    }

    @DeleteMapping("/users/{id}/{action:follow|block|mute}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @PathVariable String action) {
        graph.relation(actor(j), id, "un" + action);
    }

    @GetMapping("/users/{id}/{type:followers|following|requests}")
    List<Map<String, Object>> graph(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @PathVariable String type, @RequestParam(required = false) UUID after, @RequestParam(defaultValue = "20") int limit) {
        return graph.list(actor(j), id, type, after, limit);
    }

    @PostMapping("/posts")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> create(@AuthenticationPrincipal Jwt j, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Contracts.CreatePost p) {
        return s.views(List.of(posts.create(actor(j), key, p))).getFirst();
    }

    @GetMapping("/posts/{id}")
    Map<String, Object> post(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        return s.visible(actor(j), id);
    }

    @PatchMapping("/posts/{id}")
    Map<String, Object> edit(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Contracts.EditPost p) {
        return s.views(List.of(posts.edit(actor(j), id, p))).getFirst();
    }

    @DeleteMapping("/posts/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        posts.delete(actor(j), id);
    }

    @GetMapping("/feed/home")
    Contracts.Page home(@AuthenticationPrincipal Jwt j, @RequestParam(defaultValue = "chronological") String mode, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return feeds.home(actor(j), mode, cursor, limit, null);
    }

    @GetMapping("/users/{id}/posts")
    Contracts.Page profileFeed(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return feeds.home(actor(j), "chronological", cursor, limit, id);
    }

    @PutMapping("/posts/{id}/likes/me")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void like(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        posts.like(actor(j), id, true);
    }

    @DeleteMapping("/posts/{id}/likes/me")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void unlike(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        posts.like(actor(j), id, false);
    }

    @PostMapping("/posts/{id}/comments")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> comment(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @Valid @RequestBody Contracts.Comment c) {
        return posts.comment(actor(j), id, c);
    }

    @GetMapping("/posts/{id}/comments")
    List<Map<String, Object>> comments(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000000") UUID after) {
        s.visible(actor(j), id);
        return s.rows("select * from comments where post=? and not deleted and id>? order by id limit 50", id, after);
    }

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void deleteComment(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        posts.deleteComment(actor(j), id);
    }

    @GetMapping("/feed/explore")
    List<Map<String, Object>> explore(@AuthenticationPrincipal Jwt j) {
        UUID viewer = actor(j);
        s.active(viewer);
        return s.client().sql("select p.id,p.author,p.caption,p.created_at from posts p join users u on u.id=p.author where " + Store.VISIBLE + " AND p.visibility='PUBLIC' AND NOT u.private AND NOT EXISTS(select 1 from mutes m where m.actor=:viewer and m.target=p.author) order by p.created_at desc,p.id desc limit 50").param("viewer", viewer).query().listOfRows();
    }

    @PostMapping("/posts/{id}/impressions")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void impression(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID event) {
        s.visible(actor(j), id);
        s.exec("insert into impressions(id,actor,post) values(?,?,?) on conflict do nothing", event, actor(j), id);
    }

    @PostMapping("/media/uploads")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> upload(@AuthenticationPrincipal Jwt j, @Valid @RequestBody Contracts.Upload u) {
        return media.initiate(actor(j), u);
    }

    @PostMapping("/media/uploads/{id}/complete")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void complete(@AuthenticationPrincipal Jwt j, @PathVariable UUID id) {
        media.complete(actor(j), id);
    }

    @GetMapping("/media/{id}/download")
    Map<String, String> download(@AuthenticationPrincipal Jwt j, @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean thumbnail) {
        return media.download(actor(j), id, thumbnail);
    }
}
