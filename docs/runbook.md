# Operations

Deploy managed multi-AZ data services; do not use local Compose credentials in production. Select the production
profile, configure AUTH_MODE=oidc and OIDC_ISSUER/OIDC_JWK_SET_URI/OIDC_AUDIENCE, and remove JWT_SECRET. Inject
DB_URL/DB_USER/DB_PASSWORD, REDIS_HOST, KAFKA_BOOTSTRAP, S3_ENDPOINT, S3_ACCESS/S3_SECRET/S3_BUCKET using external
secrets. FEED_S3_PUBLIC_ENDPOINT controls browser-reachable signing endpoint when backend S3 endpoint differs. Kafka
requires TLS/auth and RF3/minISR2 in production; default topics are replication1 for local only. Private
DNS/NetworkPolicies/TLS ingress and backups require platform integration. Apply Flyway with a singleton migration job
before rolling instances if deployments require DDL change review; current startup migration lock protects local use.

Build image, push an immutable digest and update k8s/app.yaml. `kubectl apply -f k8s/app.yaml`; only after secret and
dependencies exist. HPA CPU is initial protection, not a fan-out backlog autoscaler. Split API/worker scheduling with
WORKERS_ENABLED=false for API pods; consumer-only/worker-only toggles should be introduced before independent production
scaling. Graceful shutdown waits for active requests, database locks release on process death, pending Kafka offsets
replay. Liveness must not depend on broker health; readiness needs external database routing policy. Test backup
restores and PostgreSQL PITR quarterly.

Incident: publication failures => inspect DB saturation and lock waits before retries. Lost response => retry same
Idempotency-Key. Kafka down => posts remain durable; inspect outbox backlog/dead count, repair broker,
`UPDATE outbox_events SET dead=false,attempts=0,next_attempt=now(),claim_token=null,claimed_until=null WHERE id=... AND (claimed_until IS NULL OR claimed_until<=now())`
after root cause. Publisher crash => wait up to30s for lease expiry; claim tokens fence late checkpoints. Repeated
crashes consume the ten-attempt budget. Pause old-version workers before migrating/rolling out leased publication;
see [ADR002](decisions/002-publication-leases.md). DLT => inspect schema/cause; replay original event ID to feed.events,
never discard or mutate truth. Consumer rebalance => job inserts idempotent. Worker crash => restart; transaction
checkpoint guarantees no partial committed batch.

Feed stale => compare oldest unfinished feed_jobs.updated_at, post.created_at and outbox age; distinguish queue age from
visible freshness (truth fallback). Scale workers only if DB has capacity; throttle high-volume creators and route
future posts PULL. Rebuilding timelines: clear derived entries/jobs only during a planned maintenance window, insert
jobs from retained PUSH posts, or rebuild per viewer from approved follows; truth remains intact. Redis down => GET
fallback; mutations503 due to quota protection; restore Redis and avoid cache warming all users at once. DB primary
fails => fail requests503; promote only with fencing, then restore pool connections. Private state uncertainty => fail
closed. Regional failure => promote fenced authority only after privacy watermark validation.

Metrics available: http.server.requests, Hikari, JVM/GC, feed.cache{result}, feed.visibility.filtered,
feed.outbox.published/failures, feed.outbox.backlog/dead/leased, feed.fanout.entries/backlog. Outbox backlog excludes
exhausted events; the dead-event alert requires operator investigation. Outbox gauges refresh every60s. Kafka client
metrics are available via Spring/Micrometer. Broader latency histograms, publication-to-entry freshness, media errors
and ranking spans need additional instrumentation; do not infer them from unrelated counters. Enable
`management.metrics.distribution.percentiles-histogram.http.server.requests=true` and scrape authenticated Prometheus.
OTel bridge/exporter included: configure collector via `management.otlp.tracing.endpoint`. See monitoring alert/rules
and dashboard. No user/post labels.

Retain timeline7days, session15min (minute-batched cleanup). Outbox/events/likes/comments require policy-specific
archive/purge and partition maintenance before long-running deployment. Outbox envelope references must survive Kafka
replay retention. Impressions are synchronous durable inserts with client UUID deduplication, not an asynchronous
analytics platform; add consent/aggregation/retention before use at volume.

Media completion503 with validation busy => retry the same upload ID with bounded backoff. Each instance admits two
decodes and never queues additional decode work. Do not increase this limit without heap profiling under worst-case20MP
inputs. Invalid content400 is not retryable unless the client replaces staging bytes within the upload URL lifetime.
Completed uploads retain immutable frozen bytes. Completion still holds a per-upload database transaction during S3 I/O;
an asynchronous leased processing pipeline remains planned.

Identity verification503 => inspect HTTPS reachability, issuer/JWK endpoint responses, JVM trust store, clock
synchronization and key rotation. Fresh cached known keys continue for up to5min; new or expired key lookups fail
closed. Do not switch AUTH_MODE to development or add a shared signing secret to restore availability. Invalid token401
and metrics-scope403 are separate failure classes. Follow the [identity rotation/outage procedure](identity.md),
including the per-instance two-refreshes/sec bound.

Flyway checksum mismatch => preserve the database/history and compare the applied migration source before
reconciliation. Do not disable validation or blindly repair history to pass startup. For local verification, create a
separate database and pass its container-reachable JDBC URL via Compose DB_URL; see the verification record. This does
not establish that an existing production database can be upgraded without a migration review.
