# Social Feed — Java 21 / Spring Boot 3.5.16

A runnable modular baseline with PostgreSQL truth, durable Kafka outbox, resumable hybrid fan-out, privacy-filtered
stable feed sessions, Redis ID caching, S3 media and engagement. It is not a proven billion-user production deployment.

## Build and test

```sh
cd social-feed
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; use your Java21 installation elsewhere
mvn test                         # unit tests
mvn -Pintegration verify         # requires Docker; fails if Docker unavailable
mvn package
```

Sandbox-specific verification used `-o -Daether.syncContext.named.factory=noop` to read the preinstalled Maven cache
without writing locks outside the workspace; ordinary environments do not need these flags. Testcontainers Docker
endpoint may need `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` on OrbStack.

## Local run

```sh
export AUTH_MODE=development
unset OIDC_ISSUER OIDC_JWK_SET_URI SPRING_PROFILES_ACTIVE
export JWT_SECRET=$(openssl rand -hex 32)
docker compose up --build -d
python3 scripts/smoke.py
# Keep this same secret for minting test tokens; never use production keys locally.
```

Compose binds development ports only to localhost. Kafka is single-broker KRaft, LocalStack S3 bucket is private.
Startup retries broker publication from durable outbox. Poll `http://localhost:8080/actuator/health/readiness` before
smoke. Browser and server S3 endpoints differ in Compose; signed client URLs use localhost:9000. To run on the host
instead: `docker compose up -d postgres redis kafka s3`, then `java -jar target/social-feed-1.0.0-SNAPSHOT.jar`.

## Production identity

Use `AUTH_MODE=oidc` with the `production` profile, pinned HTTPS `OIDC_ISSUER` and `OIDC_JWK_SET_URI`, and a distinct
`OIDC_AUDIENCE`. Remove `JWT_SECRET`. Kubernetes selects the production profile. The API accepts RS256 access tokens
with canonical lowercase UUID subjects; development HMAC tokens cannot authenticate in OIDC mode.
See [identity setup, rotation and outage behavior](docs/identity.md).

## API examples

Create a trusted development identity and token:

```sh
export USER_ID=$(python3 -c 'import uuid;print(uuid.uuid4())')
export TOKEN=$(python3 scripts/token.py "$USER_ID")
curl -X PATCH localhost:8080/api/v1/users/me -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"displayName":"Ada","privateAccount":false,"active":true}'
curl -X POST localhost:8080/api/v1/posts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H 'Idempotency-Key: post-1' -d '{"caption":"Hello","visibility":"PUBLIC","state":"PUBLISHED","media":[]}'
curl 'localhost:8080/api/v1/feed/home?mode=ranked&limit=20' -H "Authorization: Bearer $TOKEN"
```

Create returns201 with id,author,caption,visibility,state,created_at,version,delivery and attached media metadata. Feed
returns `{"items":[...],"nextCursor":"..."}`; next cursor may be null. Pass the cursor and same mode/scope on the next
request. Profile PATCH bootstraps the authenticated UUID; identity provider owns registration. Relationships return204.
Post edits require version; stale versions409. Hidden/private/deleted resources404; invalid bodies400; API bodies
over1MiB413; unavailable stores503. Media initiation returns uploadId,url,headers; PUT exact bytes to URL with signed
headers then complete, attach to post, request authorized download. Thumbnail access uses `?thumbnail=true` once
processing finishes. See [OpenAPI](docs/openapi.json) for all routes.

## Source and design

- `src/main/java/com/example/socialfeed/`: Api, Contracts, SocialGraph, Posts, Feeds, Ranking, Store, Security,
  IdentityTokens,
  RequestLimits, RequestBodyLimits, EventWorkers, Media, MediaValidation and MediaProcessor.
- `src/main/resources/db/migration/`: constrained truth, durable jobs, disposable timelines/session IDs.
- `src/test/`: unit and real PostgreSQL/Redis/Kafka/S3 Testcontainers integration tests.
- [Architecture and requirements](docs/architecture.md), [fan-out ADR](docs/decisions/001-hybrid.md), [publication leases ADR](docs/decisions/002-publication-leases.md), [feed algorithms](docs/feed-generation.md), [consistency](docs/consistency.md), [capacity estimates](docs/capacity-planning.md).
- [Identity provider integration](docs/identity.md).
- [Remaining implementation priorities](docs/implementation-status.md).
- [Security/launch gates](docs/security.md), [25 failure scenarios](docs/failure-analysis.md), [35-question review](docs/architecture-review.md), [operations](docs/runbook.md).
- `compose.yml`, `Dockerfile`, `k8s/app.yaml`, `.github/workflows/verify.yml`, `monitoring/`, `benchmarks/`.

## Verification

See [executed checks and phase evidence](docs/verification.md): 55 passing tests, a rebuilt/running Docker stack and
actual authenticated HTTP/S3 smoke checks. The verification instance uses http://localhost:28080 and isolated dependency
ports, with a separate `feed_identity_verification` database preserving the original verification data. See the
reproduction commands in the verification record. An
earlier-revision [small local feed benchmark](benchmarks/local-results.md) completed 600 requests at
approximately 20 RPS with zero errors and p99 30.06 ms; this does not establish production capacity.

## Limits

Sessions cover at most1000 recent candidates, expire15min and do not support endless history. Truth joins and row-by-row
snapshot inserts limit throughput; large-follow graphs need a separate candidate index. Likes serialize on a post;
source COUNT is correct but hot-post-expensive. Redis outage permits GET fallback and rejects mutations. Signed media
revocation takes up to30s. Images are validated at completion (10MiB/20MP); MP4 admission checks only the file-type
header. Image thumbnails are bounded20MP background jobs; video transcoding/multipart uploads, malware scanning, full
audit/erasure, personalized recommendation/diversity and multi-region deployment are not complete. Discovery is a recent
public feed, not a sophisticated trending engine. Impressions are durable synchronous inserts, not a shipped analytics
pipeline. Kafka carries post events only. No live push notifications; clients refresh their feed. Only a small local
benchmark has been executed; production latency,availability and million-RPS capacity remain unverified. Kubernetes
manifests are deployment scaffolding and require platform services,secrets,TLS and restore drills before use.
