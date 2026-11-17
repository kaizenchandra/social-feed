# Verification record — 2026-11-14

## Latest verification — configurable production identity

- Full Java21 `-Pintegration verify` command below exited0: **55 tests (28 unit/security +27 container integration),
  zero failures/errors/skips**. Runnable JAR packaged. Existing PostgreSQL/Redis/Kafka/S3 integration tests remain
  passing.
- Added17 tests:12 token policy/rotation/cache tests,4 Spring configuration startup tests and1 HTTP security-chain test.
  These sign real RSA/HMAC tokens and simulate trusted HTTPS JWK responses without contacting an external identity
  provider.
- Executed acceptance/rejection of issuer, audience, expiration, not-before, canonical UUID subject, signature,
  unsupported algorithms and unmatched certificate-thumbprint claims; unknown-key flood refresh bounds; new-key
  rotation; fresh-cache issuer outage behavior; expired-cache refusal; malformed/oversized key responses, and rejection
  of remote error responses without buffering their bodies.
- Spring startup tests verify OIDC without a shared secret, fail-closed missing/misspelled configuration, and production
  rejection of a development-mode override. Decoder creation performs no issuer network request.
- HTTP tests use actual bearer tokens through the real security filter chain:200 for trusted identity,401 for
  invalid/development credentials in OIDC mode,403 without metrics scope,200 with scope, and503 with Retry-After when
  signing-key verification is unavailable. The first outage test exposed Spring's default unhandled
  AuthenticationServiceException; the explicit failure handler fixed it and the final suite passes.
- The development token CLI executed successfully; OIDC/production environments, short signing secrets and invalid
  subjects were rejected without emitting a token.
- The first Docker smoke attempt encountered V1–V3 Flyway checksum mismatches in the existing `feed` verification
  database. This increment did not edit those migration files. The original database and history were preserved; a
  separate `feed_identity_verification` database was created for the current build. Compose now accepts a DB_URL
  override, and the reproduction commands below select that database. Existing-database migration reconciliation remains
  a separate review task; validation was not disabled.
- Final multi-stage Docker build completed and the development app at localhost:28080 passed the expanded HTTP/S3 smoke
  script with all three PASS lines. The isolated database applied V1–V3 successfully; scheduled outbox publication and
  fan-out drained. The old database was not repaired or deleted.
- External IdP provisioning, real TLS timeout/redirect drills, live issuer rotation, token introspection and
  opaque-subject mapping remain unverified or unimplemented. No production throughput benchmark was executed.

## Previous verification — publication recovery and upload boundaries

- Full Java21 `-Pintegration verify` command below exited0: **38 tests (11 unit +27 integration), zero
  failures/errors/skips**. PostgreSQL17, Redis7.4, Kafka3.9.1 and LocalStack4.10.0 ran in Testcontainers; all three
  Flyway migrations applied. Runnable JAR packaged successfully.
- New recovery tests prove Kafka send occurs outside a database transaction and permits a separate connection to lock
  the event row, concurrent claims cannot own the same unexpired lease, expiry allows takeover, stale tokens cannot
  checkpoint, and a crashed tenth attempt becomes dead. Existing broker-outage and duplicate replay tests still pass.
- Dead-letter tests prove a failed send throws and verify the configured `.DLT` destination. A real Kafka
  producer/consumer round trip preserves the malformed event value, key and original-offset header. This is not a full
  consumer-group offset/rebalance failure drill.
- New security tests cover valid PNG/JPEG decoding, forged/truncated/type-mismatched images, oversized image
  byte/dimension budgets, invalid MP4 headers, failed media remaining unattachable, immutable completed bytes despite
  staging overwrite, oversized upload initiation, two-slot media-validation overload/retry, and both declared/chunked
  API body limits. Successful profile/post/graph/media HTTP requests still pass through the buffering filter.
- An initial sandboxed unit run failed because Mockito dynamic self-attachment was unavailable. Surefire now loads the
  existing Mockito dependency as an explicit Java agent; the subsequent unit run and final full integration run passed
  without requiring dynamic attachment.
- Rebuilt the delivered multi-stage Docker image and refreshed the existing verification app at localhost:28080.
  Expanded `scripts/smoke.py` exited0 with three PASS lines: authenticated post/feed/privacy behavior, valid signed S3
  round trip, and forged-media plus declared/chunked oversized JSON rejection.
- Live verification database inspection after smoke: all three Flyway migrations successful, zero pending/dead/leased
  outbox events and zero unfinished fan-out jobs.
- No performance benchmark was rerun after these changes. The earlier small local benchmark below is historical evidence
  for the previous revision, not a performance result for this revision.

## Previous verification after correctness fixes

- Java 21 unit run:
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -o -Daether.syncContext.named.factory=noop -f social-feed/pom.xml test -q`
  exited 0.
- Full `-Pintegration verify` command below rerun: **23 tests (5 unit + 18 integration), zero failures/errors/skips**.
  Real PostgreSQL, Redis, Kafka and LocalStack containers started and migrations applied. This also packages the
  runnable JAR.
- Five added regressions cover malformed cursor fields, stale unfollowed/muted timeline entries excluded before snapshot
  truncation, repeated approved private follows, public follow cleanup of obsolete requests, and atomic rollback of
  post/outbox on unavailable media.
- Rebuilt the multi-stage Docker image and refreshed only the existing `social-feed-verification` application's
  container. Authenticated HTTP and presigned S3 smoke script rerun against port 28080: both PASS lines, exit 0.
- Added fixed-seed uniform/Zipf-like API seeding and publication/retry/graph/like/cursor benchmark workloads. Seeder
  executed with 20 users, five follows/user and five posts/user. All five operation scripts executed in short validation
  runs with zero errors.
- Executed a 30-second, 20-RPS feed benchmark: 600 successful requests, zero dropped iterations, p99 30.06 ms.
  See [measured results and limitations](../benchmarks/local-results.md). This supersedes earlier statements below that
  no benchmark was run; high-scale targets remain unverified.

## Earlier baseline verification

Executed with Oracle Java21.0.13 and Maven3.10.0 (Maven explicitly launched with JAVA_HOME pointing at Java21).
Boot3.5.16 resolved both from local cache and from Maven Central during Docker build.

## Executed

-

`JAVA_HOME=$(/usr/libexec/java_home -v 21) DOCKER_HOST=unix:///Users/MWGBH/.orbstack/run/docker.sock mvn -o -Daether.syncContext.named.factory=noop -f social-feed/pom.xml -Pintegration verify -q`:
exit0. 4 unit +14 integration tests; zero failures/errors/skips. Test reports live in target/surefire-reports.

- PostgreSQL17, Redis7.4, Kafka3.9.1 and LocalStack4.10.0 Testcontainers were actually started. Tests cover constrained
  concurrent follows/likes, private follow approval, block/current visibility, snapshot insertion isolation, optimistic
  updates, deletion, duplicate events,501-follower checkpoint rollback/resume, Redis read failure, broker publication
  and injected broker failure,HTTP private-post denial,media ownership and immutable completion,image
  thumbnails,celebrity PULL routing,public-to-private session filtering,timestamp ties and cursor expiry.
- Delivered multi-stage Dockerfile built successfully. Compose started API,PostgreSQL,Redis,Kafka and private
  S3-compatible LocalStack on separate verification ports to preserve existing applications.
- `JWT_SECRET=<local verification secret> BASE_URL=http://localhost:28080 python3 scripts/smoke.py`: exit0; actual
  signed JWT HTTP requests created profiles/follows/posts, replayed an idempotent post, retrieved eligible
  feed,liked,blocked and verified filtering. Real presigned PUT,completion,media attachment and signed GET round-trip
  passed.
- Live database inspection after smoke: unpublished outbox0,unfinished fan-out jobs0,delivered timeline entries1. This
  checks the actual scheduled publisher,Kafka listener and background worker rather than just a direct method call.

## Reproduce this verification stack

From social-feed/:

```sh
export AUTH_MODE=development
unset OIDC_ISSUER OIDC_JWK_SET_URI SPRING_PROFILES_ACTIVE
export JWT_SECRET=local-verification-secret-at-least-32-characters
export POSTGRES_PORT=25432 REDIS_PORT=26379 KAFKA_PORT=29092 S3_PORT=29456 APP_PORT=28080
docker compose -p social-feed-verification up -d postgres redis kafka s3
# Create the separate verification database once; preserve any existing feed database.
docker exec -i social-feed-verification-postgres-1 psql -v ON_ERROR_STOP=1 -U feed -d postgres <<'SQL'
SELECT 'CREATE DATABASE feed_identity_verification OWNER feed'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='feed_identity_verification')
\gexec
SQL
export DB_URL=jdbc:postgresql://postgres:5432/feed_identity_verification
docker compose -p social-feed-verification up --build -d app
BASE_URL=http://localhost:28080 python3 scripts/smoke.py
```

These are deliberately local credentials. Use a fresh secret in a real deployment. Stack is left running on these ports
for inspection. `docker compose -p social-feed-verification down` with the same environment stops only this project's
services and preserves PostgreSQL volume; add no volume-deletion option unless data erasure is intended.

## Remaining unverified targets

The earlier baseline run had no benchmark; the subsequent small local result is recorded above. No production-scale
benchmark was executed. Million RPS,p99<200ms,availability,freshness SLOs,large celebrity graphs,multi-AZ
failover,regional recovery,Kubernetes deployment and live CI execution remain unverified. Failure matrix distinguishes
executed checks from proposed drills. Video transcode,malware/moderation pipeline,full privacy
erasure/audit,asynchronous analytics and distributed candidate indexes are not implemented. This is a functional
baseline with documented launch gates,not fulfillment of every production acceptance criterion.

## Phase evidence

1. Requirements/HLD/LLD/capacity/ADR: docs and Flyway schema.
2. Graph/posts/security: working APIs,concurrency/privacy/optimistic-lock tests.
3. Pull feed: set-based truth retrieval,stable cursor/timestamp/privacy tests.
4. Push: durable outbox,real broker publication,job/checkpoint rollback/resume and live worker evidence.
5. Hybrid: threshold-frozen per-post delivery,celebrity pull test and UUID merging.
6. Ranking: normalized configurable weighted model and immutable session ordering.
7. Cache/media: Redis failure fallback,private S3 signed round-trip,background image thumbnails.
8. Engagement/security: unique likes,source-derived counts,comments,JWT,quota and read-time authorization; production
   abuse/moderation gates remain.
9. Reliability/deployment: built/running Compose,k8s scaffolding,CI workflow,metrics/dashboard/runbook; Kubernetes and
   hosted rollout unexecuted.
10. Verification:18 tests and HTTP/S3 smoke executed; k6 workload supplied,benchmark targets unverified.
