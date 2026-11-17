CREATE TABLE users
(
    id           uuid PRIMARY KEY,
    display_name varchar(100) NOT NULL,
    private      boolean      NOT NULL DEFAULT false,
    active       boolean      NOT NULL DEFAULT true,
    version      bigint       NOT NULL DEFAULT 0
);
CREATE TABLE follows
(
    follower   uuid REFERENCES users,
    author     uuid REFERENCES users,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower, author),
    CHECK (follower <> author)
);
CREATE INDEX follows_author ON follows (author, follower);
CREATE TABLE follow_requests
(
    follower uuid REFERENCES users,
    author   uuid REFERENCES users,
    PRIMARY KEY (follower, author),
    CHECK (follower <> author)
);
CREATE TABLE blocks
(
    actor  uuid REFERENCES users,
    target uuid REFERENCES users,
    PRIMARY KEY (actor, target),
    CHECK (actor <> target)
);
CREATE TABLE mutes
(
    actor  uuid REFERENCES users,
    target uuid REFERENCES users,
    PRIMARY KEY (actor, target),
    CHECK (actor <> target)
);
CREATE TABLE posts
(
    id           uuid PRIMARY KEY,
    author       uuid          NOT NULL REFERENCES users,
    caption      varchar(5000) NOT NULL,
    visibility   varchar(16)   NOT NULL CHECK (visibility IN ('PUBLIC', 'FOLLOWERS', 'SELF')),
    state        varchar(16)   NOT NULL CHECK (state IN ('DRAFT', 'PROCESSING', 'PUBLISHED', 'HIDDEN', 'DELETED')),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    version      bigint        NOT NULL DEFAULT 0,
    delivery     varchar(4)    NOT NULL CHECK (delivery IN ('PUSH', 'PULL')),
    request_key  varchar(100)  NOT NULL,
    request_hash varchar(64)   NOT NULL,
    UNIQUE (author, request_key)
);
CREATE INDEX posts_author_time ON posts (author, created_at DESC, id DESC) WHERE state='PUBLISHED';
CREATE INDEX posts_time ON posts (created_at DESC, id DESC) WHERE state='PUBLISHED';
CREATE TABLE likes
(
    post  uuid REFERENCES posts,
    actor uuid REFERENCES users,
    PRIMARY KEY (post, actor)
);
CREATE TABLE comments
(
    id         uuid PRIMARY KEY,
    post       uuid          NOT NULL REFERENCES posts,
    actor      uuid          NOT NULL REFERENCES users,
    body       varchar(2000) NOT NULL,
    created_at timestamptz   NOT NULL DEFAULT now(),
    deleted    boolean       NOT NULL DEFAULT false
);
CREATE INDEX comments_post ON comments (post, created_at, id) WHERE NOT deleted;
CREATE TABLE outbox_events
(
    id             uuid PRIMARY KEY,
    entity         uuid        NOT NULL,
    entity_version bigint      NOT NULL,
    event_type     varchar(40) NOT NULL,
    schema_version int         NOT NULL DEFAULT 1,
    correlation_id uuid        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published      boolean     NOT NULL DEFAULT false,
    attempts       int         NOT NULL DEFAULT 0,
    next_attempt   timestamptz NOT NULL DEFAULT now(),
    dead           boolean     NOT NULL DEFAULT false
);
CREATE INDEX outbox_pending ON outbox_events (next_attempt, created_at) WHERE NOT published AND NOT dead;
CREATE TABLE feed_jobs
(
    post           uuid PRIMARY KEY REFERENCES posts,
    after_follower uuid,
    done           boolean     NOT NULL DEFAULT false,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE feed_entries
(
    viewer     uuid REFERENCES users,
    post       uuid REFERENCES posts,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (viewer, post)
);
CREATE INDEX feed_entries_time ON feed_entries (viewer, created_at DESC, post DESC);
CREATE TABLE feed_sessions
(
    id              uuid PRIMARY KEY,
    viewer          uuid        NOT NULL REFERENCES users,
    mode            varchar(20) NOT NULL,
    ranking_version int         NOT NULL,
    scope           uuid,
    expires_at      timestamptz NOT NULL
);
CREATE INDEX sessions_expiry ON feed_sessions (expires_at);
CREATE TABLE feed_session_items
(
    session  uuid REFERENCES feed_sessions ON DELETE CASCADE,
    position int NOT NULL,
    post     uuid REFERENCES posts,
    PRIMARY KEY (session, position),
    UNIQUE (session, post)
);
CREATE TABLE post_media
(
    id           uuid PRIMARY KEY,
    owner        uuid         NOT NULL REFERENCES users,
    object_key   varchar(200) NOT NULL UNIQUE,
    content_type varchar(40)  NOT NULL,
    bytes        bigint       NOT NULL CHECK (bytes BETWEEN 1 AND 104857600),
    complete     boolean      NOT NULL DEFAULT false,
    post         uuid REFERENCES posts,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX post_media_post ON post_media (post);
CREATE TABLE impressions
(
    id         uuid PRIMARY KEY,
    actor      uuid REFERENCES users,
    post       uuid REFERENCES posts,
    created_at timestamptz NOT NULL DEFAULT now()
);
