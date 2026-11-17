# MASTER PROMPT: Design and Develop a Production-Grade Scalable Social Media Feed System Using Java 21 and Spring Boot

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Staff Backend Engineer, Java
Performance Engineer, Database Architect, and Site Reliability Engineer** with 15+ years of experience designing
high-scale platforms similar to Facebook, Instagram, X (Twitter), LinkedIn, and TikTok.

Your task is to **design, develop, test, benchmark, document, and deploy an end-to-end Scalable Social Media Feed
System** using **Java 21 and Spring Boot**.

Approach this as a real enterprise engineering project rather than merely a system design interview exercise.

You are responsible for delivering:

1. Functional and non-functional requirements
2. High-Level Design (HLD)
3. Low-Level Design (LLD)
4. Data models and database schema
5. REST API contracts
6. Feed generation algorithms
7. Fan-out on Write implementation
8. Fan-out on Read implementation
9. Hybrid fan-out strategy
10. Feed ranking and pagination
11. Social graph management
12. Post creation and media processing
13. Distributed caching
14. Event-driven processing
15. Concurrency and consistency handling
16. Security and privacy
17. Observability
18. Automated testing
19. Load testing and performance optimization
20. Docker and Kubernetes deployment

Do not stop after architecture diagrams, pseudocode, or illustrative examples.

**Deliver a complete, runnable, maintainable, testable implementation with source code, infrastructure configuration,
and operational documentation.**

### Mandatory Engineering Principles

- Use Java 21 and a verified compatible Spring Boot release.
- Follow repository conventions when an existing project is provided.
- Apply SOLID, DDD, and Clean/Hexagonal Architecture pragmatically.
- Prefer modular boundaries over premature microservice decomposition.
- Keep transactions short and explicit.
- Use bounded retries, queues, connection pools, and timeouts.
- Address idempotency, ordering, concurrency, authorization, and failure recovery.
- Avoid introducing Kafka, Elasticsearch, Cassandra, or extra services unless justified.
- Separate source-of-truth data from disposable feed materializations.
- Never invent benchmark results, executed tests, or production-readiness claims.
- Explain significant design trade-offs and document assumptions.
- Make routine technical decisions independently and proceed with implementation.

---

# PART A — PRODUCT OBJECTIVE

## 2. PRIMARY OBJECTIVE

Build a horizontally scalable social media platform where users can:

- Register and manage profiles
- Follow and unfollow other users
- Create posts
- Upload images and videos
- View personalized home feeds
- View user profile feeds
- Like and unlike posts
- Comment on posts
- Delete or edit posts
- Manage privacy settings
- Receive feed updates
- Explore trending or recommended content
- Paginate large feeds efficiently

The system must support millions of concurrently active users and a future scale-out path toward hundreds of millions or
billions of registered users.

### Example Scenario

User A follows User B, User C, and User D.

When User B publishes a new post:

1. The post is validated and persisted.
2. Media references are associated with the post.
3. A durable post-created event is recorded.
4. Background processing generates relevant feed entries.
5. Followers' home feeds become eligible to display the post.
6. Feed caches are updated or invalidated according to the selected strategy.
7. User A retrieves their feed.
8. The system combines materialized and on-demand candidates where required.
9. Visibility rules are checked.
10. Candidates are ranked and paginated.
11. The API returns a consistent response.
12. The system records impressions or engagement events asynchronously.

The platform must handle celebrity accounts with millions of followers without overwhelming the messaging infrastructure
or database.

---

# PART B — FUNCTIONAL REQUIREMENTS

## 3. CORE FEATURES

### 3.1 User and Profile Management

Implement:

- User registration or integration with an identity provider
- Profile creation and updates
- Public/private accounts
- Account deactivation
- User blocking
- User muting
- Profile feeds
- User statistics

### 3.2 Social Graph

Implement:

- Follow
- Unfollow
- Follow request for private accounts
- Accept/reject follow requests
- Block/unblock
- Mute/unmute
- Follower count
- Following count
- Following/follower pagination

Define visibility semantics for private accounts, blocks, and deleted relationships.

### 3.3 Post Management

Implement:

- Create post
- Edit post
- Delete post
- Retrieve post
- List user posts
- Attach images/videos
- Post captions
- Visibility settings
- Post timestamps
- Optional hashtags

Support post states such as:

- DRAFT
- PUBLISHED
- PROCESSING
- HIDDEN
- DELETED

Define permitted state transitions.

### 3.4 Home Feed

Implement:

- Personalized home feed
- Reverse-chronological feed
- Ranked feed
- Cursor-based pagination
- Incremental feed refresh
- Feed deduplication
- Filtering hidden/deleted posts
- Privacy-aware visibility

### 3.5 Engagement

Implement:

- Like/unlike
- Comment creation/deletion
- Engagement counters
- Optional reshare
- Impression events

Design idempotent like/unlike behavior.

Protect counters against concurrency races and duplicate events.

### 3.6 Media

Support:

- Image upload
- Video upload
- Secure media access
- Media metadata
- Object storage
- CDN delivery
- Background thumbnail generation
- Optional video transcoding

Use S3-compatible storage for binary media.

Avoid storing large image/video payloads in PostgreSQL.

---

# PART C — SCALE AND NON-FUNCTIONAL REQUIREMENTS

## 4. TARGET SCALE

Treat the following numbers as design assumptions requiring verification.

| Dimension              | Target                               |
|------------------------|--------------------------------------|
| Registered users       | 1 billion                            |
| Daily active users     | 100 million                          |
| Peak concurrent users  | 10 million                           |
| New posts/day          | 100 million                          |
| Feed reads/day         | 10 billion                           |
| Peak feed reads        | 1 million requests/second            |
| Average followers/user | Assumption to calculate              |
| Celebrity followers    | 10 million–100 million               |
| Feed API latency       | p99 below 200 ms within a region     |
| Feed availability      | 99.99% target                        |
| Feed page size         | 20–50 posts                          |
| Media storage          | Petabyte-scale design                |
| Feed generation        | Eventual consistency                 |
| Deployment             | Multi-AZ with multi-region extension |

Distinguish:

- Registered users from active users
- Post writes from feed reads
- Feed requests from individual post retrievals
- Fan-out events from successfully delivered feed entries
- Metadata traffic from media bandwidth
- Source-of-truth storage from derived timeline storage

Calculate traffic from explicit usage assumptions rather than asserting that all targets are simultaneously achievable.

Define separate SLOs for post publication, feed freshness, feed reads, and media delivery.

---

# PART D — HIGH-LEVEL DESIGN

## 5. ARCHITECTURE ALTERNATIVES

Compare:

### Option A — Fan-out on Write (Push Model)

When a post is created, precompute feed entries for followers.

Evaluate:

- Feed read latency
- Write amplification
- Celebrity amplification
- Storage requirements
- Follower growth
- Queue pressure
- Eventual consistency

### Option B — Fan-out on Read (Pull Model)

When a user requests their feed, dynamically fetch recent posts from followed accounts.

Evaluate:

- Read amplification
- Query complexity
- Feed freshness
- Cache efficiency
- Celebrity support
- Ranking cost

### Option C — Hybrid Fan-out

Materialize ordinary creators' posts while fetching high-follower or high-volume creators' posts on demand.

Evaluate:

- Threshold selection
- Feed assembly latency
- Duplicate elimination
- Consistency
- Operational complexity
- Cache invalidation
- Ranking integration

**Recommend a hybrid approach for the target scale, but evaluate whether a simpler baseline should be implemented
first.**

Do not assume the hybrid strategy is always optimal for every workload.

Provide an Architecture Decision Record explaining the selection.

---

## 6. REFERENCE COMPONENTS

Consider:

1. API Gateway
2. Identity / Authentication
3. User Profile Module
4. Social Graph Module
5. Post Management Module
6. Feed Generation Engine
7. Fan-out Workers
8. Timeline Storage
9. Feed Query / Assembly Engine
10. Feed Ranking Engine
11. Redis Cache
12. PostgreSQL
13. Kafka
14. Media Storage
15. CDN
16. Engagement Processing
17. Notification Infrastructure
18. Observability

Introduce independent services only when concrete scaling or deployment boundaries require them.

Provide Mermaid diagrams for:

- Overall architecture
- Post publication
- Fan-out on write
- Fan-out on read
- Hybrid feed retrieval
- Celebrity post processing
- Post deletion and visibility changes
- Analytics event processing

Separate the synchronous user-facing path from asynchronous fan-out and analytics paths.

---

# PART E — CAPACITY PLANNING

## 7. TRAFFIC AND STORAGE ESTIMATION

Calculate:

1. Posts created per second
2. Peak write traffic
3. Feed reads per second
4. Fan-out write amplification
5. Kafka events per second
6. Timeline storage growth
7. Post metadata storage
8. Redis memory requirements
9. Social graph storage
10. Image/video storage
11. Network bandwidth
12. Database partition requirements
13. Fan-out worker count
14. Queue backlog and recovery time
15. Multi-region replication overhead

Use formulas.

Examples:

`Fan-out writes/sec = posts/sec × average eligible followers`

`Feed candidate reads/sec = feed requests/sec × candidates fetched/request`

`Fan-out catch-up time = queued work / net processing capacity`

Include explicit assumptions about follower distribution.

Analyze a skewed social graph where a small number of celebrity accounts own a disproportionate share of followers.

Explain why average follower count alone is insufficient for capacity planning.

---

# PART F — DOMAIN MODEL AND DATABASE DESIGN

## 8. LOW-LEVEL DESIGN

Design focused domain concepts such as:

- User
- UserProfile
- FollowRelationship
- FollowRequest
- BlockRelationship
- Post
- PostMedia
- PostVisibility
- FeedEntry
- FeedCursor
- FeedCandidate
- FeedRankingScore
- Like
- Comment
- FeedGenerationJob
- OutboxEvent

Use Java 21 records, enums, and sealed interfaces where appropriate.

Separate domain, application, API, and infrastructure responsibilities.

Provide:

- UML class diagram
- Sequence diagrams
- State diagrams
- Transaction boundaries
- Repository interfaces
- Error handling strategy

---

## 9. POSTGRESQL SCHEMA

Design tables for:

- users
- user_profiles
- follows
- follow_requests
- blocks
- posts
- post_media
- likes
- comments
- outbox_events

Add timeline tables or specialized stores only when required.

Define:

- Primary keys
- Foreign keys
- Unique constraints
- Indexes
- Partitioning strategy
- Optimistic locking
- Soft deletion
- Timestamp handling
- Retention policies

### Correctness requirements

- Duplicate follows are prevented.
- Duplicate likes are prevented.
- Concurrent like/unlike operations have defined behavior.
- Deleted posts cannot be exposed through stale timelines.
- Blocks and private account visibility are enforced.
- Concurrent follow/unfollow operations do not corrupt relationship state.

Explain the implications of sharding posts by author versus post ID.

Avoid designing distributed joins without accounting for their cost.

---

# PART G — FEED GENERATION ENGINE

## 10. FAN-OUT ON WRITE

Implement a working fan-out pipeline.

Example:

1. User publishes a post.
2. PostgreSQL commits the post and outbox record atomically.
3. An outbox publisher emits a post-created event.
4. Kafka routes the event to fan-out processing.
5. Workers resolve eligible follower batches.
6. Feed entries are inserted into timeline storage.
7. Feed caches are refreshed or invalidated.
8. Workers checkpoint processing progress.

### Mandatory concerns

- Follower pagination
- Batch processing
- Bounded worker concurrency
- Retry with backoff
- Idempotent feed-entry insertion
- Duplicate event consumption
- Worker crashes
- Partial fan-out completion
- Kafka rebalance
- Backpressure
- Catch-up processing
- Dead-letter handling

Do not load millions of followers into JVM memory simultaneously.

Ensure partial fan-out jobs can resume without duplicating entries.

---

## 11. FAN-OUT ON READ

Implement an on-demand feed path.

Requirements:

- Retrieve followed creators
- Fetch recent eligible posts
- Merge streams
- Deduplicate
- Apply visibility rules
- Sort or rank
- Paginate
- Bound total work and response latency

Explain the complexity of querying users who follow thousands of accounts.

Avoid unbounded queries or one database query per followed account.

Provide a scalable candidate-fetching design.

---

## 12. HYBRID FAN-OUT ENGINE

Implement policy-driven selection between push and pull.

Evaluate criteria such as:

- Follower count
- Post frequency
- Active follower ratio
- Fan-out queue utilization
- Tenant or platform configuration
- Operational cost

Ensure mode changes do not permanently omit or duplicate posts.

Define behavior when a creator transitions from push to pull or vice versa.

Use stable post IDs and deduplication across candidate sources.

---

# PART H — CELEBRITY PROBLEM

## 13. HIGH-FOLLOWER ACCOUNTS

Analyze a creator with 100 million followers publishing one post.

Address:

- Fan-out amplification
- Kafka partition hot spots
- Database write volume
- Timeline memory
- Worker queue saturation
- Tail latency
- Priority inversion
- Backpressure
- User feed freshness

Recommend a pull-based or hybrid approach for extremely high-follower creators.

Implement at least a configurable threshold-based celebrity policy.

Document limitations of a static threshold.

Ensure celebrity posts remain visible to followers without requiring all followers' timelines to be eagerly updated.

---

# PART I — FEED RANKING

## 14. RANKING ENGINE

Implement a deterministic, explainable baseline ranking strategy.

Possible features:

- Post recency
- Relationship strength
- Engagement score
- Content type
- User interests
- Creator frequency
- Content freshness

Start with a weighted ranking model rather than a complex machine-learning platform.

Example conceptual scoring:

`score = w1 × recency + w2 × engagement + w3 × affinity`

Define normalization and score ranges.

Do not combine incomparable raw metrics without normalization.

Support:

- Reverse-chronological mode
- Ranked mode
- Configurable weights
- Deterministic tie-breaking
- Ranking version
- Filtering
- Deduplication

### Important ranking considerations

- New-post cold start
- Engagement manipulation
- Popularity bias
- Feed diversity
- Recency
- Fairness
- Latency budget

Do not introduce a full ML infrastructure unless justified.

---

# PART J — FEED PAGINATION AND CONSISTENCY

## 15. CURSOR-BASED PAGINATION

Implement keyset/cursor pagination.

Avoid OFFSET pagination for deep feeds.

Define:

- Cursor encoding
- Stable ordering
- Timestamp and post-ID tie-breaker
- Ranking version
- Feed snapshot boundary
- Cursor expiration
- Duplicate prevention
- Deleted-post handling
- Newly inserted-post behavior

A cursor must not be treated merely as the last visible timestamp if ranked candidates can reorder between requests.

Choose a documented model, such as:

- Stable snapshot pagination
- Bounded feed-session materialization
- Best-effort live pagination with explicitly stated duplicate/omission behavior

For a ranked feed, prefer a stable feed-session cursor for the initial implementation if practical.

Test pagination while posts are inserted, deleted, or re-ranked concurrently.

---

## 16. FEED CONSISTENCY

Define:

- Post publication visibility
- Eventual fan-out delay
- Feed freshness
- Read-your-own-post behavior
- Follow/unfollow propagation
- Block and mute enforcement
- Post deletion propagation
- Cache staleness

Differentiate:

- Social graph truth
- Post truth
- Derived timeline entries
- Cached feed pages

**Critical invariant: A stale materialized timeline entry must not bypass current authorization or visibility rules.**

Explain how reads handle visibility changes during asynchronous fan-out.

Define what the system guarantees when a post is deleted or an account becomes private.

---

# PART K — REDIS CACHING

## 17. DISTRIBUTED CACHING

Design Redis caches for:

- Home feed candidate IDs
- User profile metadata
- Follow counts
- Post metadata
- Engagement counts
- Ranking metadata
- Trending candidates

Evaluate:

- Cache-aside
- Write-through
- Materialized timelines
- Sorted sets
- TTL
- Cache invalidation
- Cache warming
- Negative caching
- Hot-key protection

### Redis data structures

Evaluate:

- Sorted Sets for ordered feed candidates
- Hashes for compact metadata
- Strings for serialized data
- Sets for selected relationships

Define memory requirements.

Avoid treating Redis as the sole durable source of feed data unless the product explicitly accepts losing derived
timelines and supports deterministic regeneration.

---

# PART L — KAFKA AND EVENT-DRIVEN ARCHITECTURE

## 18. EVENT DESIGN

Define events such as:

- PostCreated
- PostUpdated
- PostDeleted
- UserFollowed
- UserUnfollowed
- AccountBlocked
- AccountPrivacyChanged
- PostLiked
- PostUnliked
- CommentCreated

Specify:

- Event ID
- Entity ID
- Entity version
- Event type
- Timestamp
- Schema version
- Trace correlation ID

Use partition keys that preserve ordering for operations that require it.

Explain why a single global Kafka topic ordering guarantee is not available across partitions.

### Delivery semantics

Design for at-least-once processing.

Implement:

- Transactional outbox
- Idempotent consumers
- Duplicate suppression
- Retry topics or bounded retries
- Dead-letter handling
- Consumer lag monitoring
- Schema compatibility
- Event replay

Do not claim end-to-end exactly-once effects merely because Kafka producer transactions are enabled.

---

# PART M — MEDIA UPLOAD AND DELIVERY

## 19. MEDIA ARCHITECTURE

Support image and video attachments using S3-compatible object storage.

Implement:

- Upload initiation
- Pre-signed upload URLs
- Upload completion
- Metadata verification
- Media ownership
- Secure download
- Thumbnail processing where required

For large videos, evaluate multipart upload and transcoding workers.

Avoid proxying all media traffic through the Spring Boot service.

Explain:

- CDN behavior
- Cache invalidation
- Signed URLs
- Upload validation
- Malware scanning
- Media lifecycle
- Storage retention
- Privacy-sensitive access

Do not expose private media through unrestricted public object-storage URLs.

---

# PART N — ENGAGEMENT

## 20. LIKES, COMMENTS, AND COUNTERS

Implement:

- Like
- Unlike
- Comment
- Delete comment
- Like count
- Comment count

### Consistency requirements

- One active like per user/post.
- Repeated like requests are safe.
- Counts do not permanently drift because of duplicate events.
- Deleted comments are handled correctly.
- Post deletion affects engagement visibility.

Evaluate:

- Synchronous database counter updates
- Asynchronous aggregation
- Sharded counters
- Redis buffering

Choose a strategy appropriate for the initial scale.

Document eventual consistency where used.

---

# PART O — SECURITY AND PRIVACY

## 21. SECURITY REQUIREMENTS

Implement or document:

1. OAuth2/OIDC or JWT authentication
2. Spring Security authorization
3. Private account rules
4. Post visibility
5. Block/mute enforcement
6. Media access authorization
7. Tenant isolation where applicable
8. Rate limiting
9. Spam prevention
10. Bot abuse prevention
11. Input validation
12. Content sanitization
13. Safe object-storage permissions
14. Secret management
15. Audit logging
16. Account deletion and data retention
17. Privacy-aware analytics
18. Sensitive data protection
19. Moderation integration points
20. Administrative authorization

Do not allow a user to retrieve private posts merely by guessing post IDs or bypassing feed APIs.

Ensure current permissions are enforced at every relevant retrieval path.

---

# PART P — IMPLEMENTATION STACK

## 22. TECHNOLOGY SELECTION

Use the following baseline stack.

| Layer                | Technology                                      |
|----------------------|-------------------------------------------------|
| Language             | Java 21                                         |
| Framework            | Spring Boot, verified compatible stable version |
| Build                | Maven                                           |
| API                  | Spring Web MVC                                  |
| Security             | Spring Security                                 |
| Persistence          | PostgreSQL                                      |
| ORM/data access      | Spring Data JPA or JDBC as justified            |
| Database migrations  | Flyway                                          |
| Cache                | Redis with Lettuce                              |
| Messaging            | Apache Kafka                                    |
| Object storage       | Amazon S3-compatible API                        |
| Local object storage | MinIO or equivalent                             |
| Observability        | Micrometer and OpenTelemetry                    |
| Testing              | JUnit 5, Mockito, Testcontainers                |
| Performance testing  | k6 or Gatling                                   |
| Containers           | Docker                                          |
| Orchestration        | Kubernetes                                      |
| Monitoring           | Prometheus, Grafana                             |

Verify actual dependency versions from official documentation.

Evaluate Java 21 virtual threads where blocking I/O dominates.

Do not automatically select WebFlux or virtual threads without discussing their operational implications.

Use the simplest stack that satisfies the implemented functionality.

---

# PART Q — SOURCE CODE ORGANIZATION

## 23. PROJECT STRUCTURE

Create a maintainable Maven project.

Suggested structure:

```text
social-feed/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── feed-generation.md
│   ├── consistency.md
│   ├── capacity-planning.md
│   ├── security.md
│   ├── runbook.md
│   └── decisions/
├── src/
│   ├── main/
│   │   ├── java/com/example/socialfeed/
│   │   │   ├── user/
│   │   │   ├── socialgraph/
│   │   │   ├── post/
│   │   │   ├── feed/
│   │   │   ├── engagement/
│   │   │   ├── media/
│   │   │   ├── shared/
│   │   │   ├── security/
│   │   │   └── configuration/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       └── java/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Within each domain module, separate application, domain, API, and infrastructure code where useful.

Avoid creating empty packages or excessive layers.

---

# PART R — REST API DESIGN

## 24. REQUIRED APIs

Implement authenticated REST APIs.

### User APIs

- `GET /api/v1/users/{userId}`
- `PATCH /api/v1/users/me`
- `POST /api/v1/users/{userId}/follow`
- `DELETE /api/v1/users/{userId}/follow`
- `POST /api/v1/users/{userId}/block`
- `DELETE /api/v1/users/{userId}/block`

### Post APIs

- `POST /api/v1/posts`
- `GET /api/v1/posts/{postId}`
- `PATCH /api/v1/posts/{postId}`
- `DELETE /api/v1/posts/{postId}`
- `GET /api/v1/users/{userId}/posts`

### Feed APIs

- `GET /api/v1/feed/home?limit=20&cursor=...`
- `GET /api/v1/feed/home?mode=chronological`
- `GET /api/v1/feed/home?mode=ranked`

### Engagement APIs

- `PUT /api/v1/posts/{postId}/likes/me`
- `DELETE /api/v1/posts/{postId}/likes/me`
- `POST /api/v1/posts/{postId}/comments`
- `GET /api/v1/posts/{postId}/comments`

### Media APIs

- `POST /api/v1/media/uploads`
- `POST /api/v1/media/uploads/{uploadId}/complete`

Provide example JSON requests/responses, HTTP status codes, validation, pagination, and OpenAPI documentation.

Use RFC 9457 Problem Details where compatible.

Do not trust caller-provided identity or ownership fields.

---

# PART S — RELIABILITY AND DISTRIBUTED FAILURE SCENARIOS

## 25. REQUIRED FAILURE ANALYSIS

Analyze and test these scenarios:

1. User creates a post but the response times out.
2. Post commits but Kafka publication fails.
3. Kafka delivers PostCreated twice.
4. Fan-out worker crashes midway through follower processing.
5. Celebrity post creates excessive fan-out load.
6. Feed cache becomes stale.
7. Redis becomes unavailable.
8. PostgreSQL primary fails.
9. Kafka consumer lag grows significantly.
10. A user unfollows while fan-out is processing.
11. A user blocks another user while feed entries remain cached.
12. Post is deleted after fan-out.
13. Private account becomes public or vice versa.
14. A user follows 100,000 accounts.
15. Thousands of posts have identical timestamps.
16. Feed pagination occurs while new posts arrive.
17. Engagement events arrive out of order.
18. Like counters drift.
19. Media upload succeeds but post creation fails.
20. A viral post causes a hot-key bottleneck.
21. A regional outage occurs.
22. An application instance crashes during ranking.
23. A feed-generation job is retried after partial success.
24. Mixed service versions consume different event schemas.
25. A deleted or blocked post remains in a cached feed.

For each scenario provide:

- Failure mechanism
- Data integrity or privacy risk
- User-visible behavior
- Prevention
- Recovery
- Test strategy
- Residual risk

Privacy and authorization correctness must take precedence over cache freshness.

---

# PART T — TESTING

## 26. UNIT TESTS

Cover:

- Follow/unfollow behavior
- Follow-request state transitions
- Block/mute rules
- Post lifecycle
- Post visibility
- Feed candidate merging
- Deduplication
- Chronological ordering
- Ranking calculation
- Cursor encoding/decoding
- Like idempotency
- Comment validation

## 27. INTEGRATION TESTS

Use Testcontainers for:

- PostgreSQL
- Redis
- Kafka
- S3-compatible object storage where implemented

Verify:

- Database constraints
- Post creation and outbox consistency
- Event publication
- Fan-out processing
- Feed retrieval
- Cache behavior
- Authorization
- Pagination
- Media access

## 28. CONCURRENCY TESTS

Verify:

- Concurrent follows
- Duplicate likes
- Concurrent post updates
- Post deletion versus feed retrieval
- Privacy changes during fan-out
- Duplicate event processing
- Out-of-order events
- Concurrent pagination
- Multiple fan-out workers processing the same batch

## 29. FAILURE TESTS

Simulate:

- Kafka outage
- Redis outage
- Database timeout
- Worker crash
- Lost HTTP response
- Duplicate event
- Consumer rebalance
- Partial fan-out
- Queue saturation
- Cache stampede

Distinguish tests actually executed from proposed tests.

---

# PART U — PERFORMANCE ENGINEERING

## 30. LOAD TESTING

Build reproducible workloads for:

- 1,000 feed requests/second
- 10,000 feed requests/second
- 100,000 feed requests/second
- 1 million feed requests/second

Separately benchmark:

- Post creation throughput
- Follower-graph queries
- Fan-out throughput
- Feed assembly
- Ranking
- Cache hit/miss behavior
- Celebrity posts
- Engagement traffic

Test:

- Uniform follower distribution
- Zipfian follower distribution
- Large celebrity accounts
- High write amplification
- Read-heavy traffic
- Redis outage
- Kafka lag
- Rebuilding feed caches

Measure:

- Offered and achieved RPS
- p50/p95/p99 latency
- Error rates
- Feed freshness lag
- Fan-out processing rate
- Fan-out backlog
- Cache hit ratio
- Redis memory utilization
- PostgreSQL CPU and QPS
- Kafka consumer lag
- Ranking time
- JVM heap and GC
- Network bandwidth

Never invent benchmark numbers.

If the test environment cannot reach target scale, provide reproducible scripts and label the performance goal as
unverified.

---

# PART V — MULTI-REGION DESIGN

## 31. GLOBAL FEED ARCHITECTURE

Design a multi-region extension.

Evaluate:

- Geographic routing
- Region-local feed caches
- Global post identifiers
- Cross-region replication
- Social graph consistency
- Regional timeline storage
- User data residency
- Post deletion propagation
- Privacy changes
- Disaster recovery

Compare:

1. Single-region multi-AZ
2. Active-passive multi-region
3. Active-active multi-region

Define consistency guarantees.

Do not assume asynchronous replication supports immediate globally consistent privacy changes.

Explain how authorization is handled during regional partitions and stale state.

---

# PART W — DEPLOYMENT AND OBSERVABILITY

## 32. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfile
- Docker Compose
- PostgreSQL
- Redis
- Kafka
- S3-compatible local object storage
- Kubernetes manifests or Helm chart
- Resource requests and limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Rolling deployments
- CI/CD pipeline

Ensure the local environment can execute an end-to-end post-to-feed scenario.

---

## 33. METRICS, LOGGING, AND TRACING

Expose:

- Post creation throughput
- Feed request throughput
- Feed latency
- Cache hit/miss ratio
- Fan-out queue depth
- Fan-out processing latency
- Fan-out failures
- Feed freshness lag
- Kafka consumer lag
- Ranking latency
- Engagement event rate
- Unauthorized visibility filtering
- Media processing failures

Use Micrometer, Prometheus, Grafana, and OpenTelemetry.

Avoid high-cardinality labels such as user IDs and post IDs.

Provide dashboards, alert recommendations, and incident runbooks.

---

# PART X — IMPLEMENTATION PHASES

## 34. END-TO-END EXECUTION PLAN

Execute the project in phases.

**Phase 1 — Requirements and Architecture**

Finalize scope, consistency model, architecture, database design, fan-out strategy, and capacity assumptions.

**Phase 2 — Core Social Graph and Posts**

Implement user profiles, follow relationships, privacy, posts, validation, migrations, and authentication.

**Phase 3 — Chronological Feed**

Implement a correct feed using fan-out on read, cursor pagination, visibility checks, and tests.

**Phase 4 — Fan-out on Write**

Implement transactional outbox, Kafka publishing, background fan-out, materialized timelines, retries, and idempotency.

**Phase 5 — Hybrid Fan-out**

Implement celebrity routing, push/pull candidate merging, and deduplication.

**Phase 6 — Feed Ranking**

Implement configurable ranking and stable pagination behavior.

**Phase 7 — Redis and Media**

Implement caching, invalidation, media upload, and CDN-ready access patterns.

**Phase 8 — Engagement and Security**

Implement likes, comments, counters, security controls, moderation hooks, and privacy enforcement.

**Phase 9 — Reliability and Deployment**

Add metrics, tracing, fault handling, Docker, Kubernetes, and CI/CD.

**Phase 10 — Verification and Performance**

Run tests, benchmark representative workloads, optimize proven bottlenecks, and document limitations.

For every phase:

- Implement actual source files.
- Keep the application buildable.
- Run appropriate tests.
- Fix meaningful defects.
- Preserve existing functionality.
- Document important decisions.
- State verification evidence and remaining risks.

If repository and command-execution tools are available, use them to implement the project directly.

If unavailable, provide complete source files with paths and runnable commands. Never claim that code was built or
tested without execution.

---

# PART Y — REQUIRED DELIVERABLES

## 35. COMPLETE PROJECT OUTPUTS

Deliver:

### 1. Requirements

- Functional requirements
- Non-functional requirements
- Assumptions
- Acceptance criteria

### 2. High-Level Design

- Architecture diagrams
- Fan-out strategy comparison
- Capacity planning
- Scaling approach
- Failure scenarios

### 3. Low-Level Design

- UML diagrams
- Domain model
- Database schema
- Sequence diagrams
- Ranking and pagination algorithms

### 4. Source Code

- Java 21 Spring Boot project
- Social graph
- Post management
- Chronological feed
- Fan-out on write
- Fan-out on read
- Hybrid feed
- Feed ranking
- Engagement
- Redis caching
- Kafka integration
- Media storage
- Security

### 5. Automated Tests

- Unit tests
- Integration tests
- Concurrency tests
- Privacy/security tests
- Failure-path tests

### 6. Infrastructure

- Docker
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Monitoring

### 7. Performance

- Load-test scripts
- Actual results where available
- Capacity estimates
- Scalability limitations

### 8. Documentation

- README
- Architecture Decision Records
- API specifications
- Deployment guide
- Troubleshooting
- Runbooks
- Known limitations

---

# PART Z — MANDATORY ARCHITECTURE REVIEW

## 36. SYSTEM DESIGN QUESTIONS

Answer these as part of the final review.

1. How would you design Facebook News Feed for a billion users?
2. How does fan-out on write work?
3. How does fan-out on read work?
4. Why is hybrid fan-out often preferred?
5. How do you handle celebrity accounts?
6. How do you avoid write amplification?
7. How do you handle users following thousands of accounts?
8. How should the social graph be stored?
9. How do you partition posts and timelines?
10. How do you guarantee post ID uniqueness?
11. How does Kafka support feed generation?
12. How does the transactional outbox prevent missed events?
13. How do you make fan-out processing idempotent?
14. How do you recover partially completed fan-out jobs?
15. How do you prevent deleted posts from appearing?
16. How do you enforce blocks and private accounts?
17. How does Redis caching reduce feed latency?
18. What happens when Redis fails?
19. How do you prevent feed cache stampedes?
20. How do you merge push and pull feeds?
21. How do you deduplicate posts?
22. How do you rank feed candidates?
23. How do you implement stable cursor pagination?
24. How do you handle new posts during pagination?
25. How do you prevent engagement counter drift?
26. How do you handle viral posts?
27. How do you deliver media efficiently?
28. How do you scale feed reads to one million RPS?
29. How do you reduce fan-out backlog?
30. How do you measure feed freshness?
31. How do you handle regional outages?
32. How do you propagate privacy changes globally?
33. What consistency guarantees does the feed provide?
34. How do you benchmark the feed correctly?
35. What architecture changes are needed at 100 million daily active users?

Explain correctness, performance, scalability, failure handling, and trade-offs for each.

---

# DEFINITION OF DONE

## 37. ACCEPTANCE CRITERIA

The implementation is considered complete only when:

- Java 21 project builds.
- User and social graph APIs function.
- Post creation is durable.
- The home feed returns eligible posts.
- Cursor pagination is correct under its documented model.
- Fan-out on read works.
- Fan-out on write works.
- Hybrid fan-out handles celebrity accounts.
- Duplicate feed entries are prevented.
- Kafka events are processed idempotently.
- Partial fan-out jobs can recover.
- Current privacy and blocking rules are enforced.
- Deleted posts do not bypass visibility checks.
- Redis caching behaves according to documented consistency guarantees.
- Media uploads and access controls function.
- Likes/comments work with correct concurrency behavior.
- Critical unit and integration tests pass.
- Failure-path tests cover important recovery scenarios.
- Docker Compose supports local execution.
- Kubernetes deployment configuration is provided.
- Operational metrics are exposed.
- Benchmark scripts are reproducible.
- Performance results are measured or explicitly labeled estimates.
- Remaining limitations and risks are documented.

A passing local build does not automatically imply production readiness or million-RPS scalability.

---

# FINAL EXECUTION INSTRUCTIONS

Design and build this as a **real, production-oriented, scalable social media feed platform**.

Prioritize:

1. **Correctness:** Durable posts and accurate social relationships.
2. **Privacy:** No unauthorized post or media exposure.
3. **Feed quality:** Relevant, deduplicated, consistently paginated feeds.
4. **Scalability:** Efficient fan-out and horizontal read scaling.
5. **Reliability:** Idempotent event processing and recoverable fan-out.
6. **Performance:** Low-latency feed retrieval.
7. **Maintainability:** Pragmatic domain boundaries and clean Java 21 code.
8. **Observability:** Diagnosable performance and processing failures.
9. **Reproducibility:** Automated tests, local infrastructure, and deployment configuration.

Begin with a concise architectural assessment and select the implementation approach.

Then complete each development phase with actual source code, tests, and infrastructure.

Use available tools to create files, compile the project, run tests, and fix defects.

At completion report:

- What was implemented
- Why the architecture was selected
- Source structure and important files
- Exact build and run commands
- Tests actually executed
- Benchmark results, if measured
- Known limitations
- Remaining production risks

**Do not stop after HLD or LLD. Deliver the complete system end to end, including source code, feed algorithms,
integration tests, infrastructure, and operational documentation.**