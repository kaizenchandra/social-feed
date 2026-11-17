# Implementation status and remaining work

This is an implementation backlog, not a statement that the full production definition of done is met. The README and
verification record describe executed checks.

## Completed identity integration

- Configurable OIDC/JWK RS256 validation with pinned issuer/audience, canonical UUID subjects, mandatory expiration and
  no development-key fallback.
- Production startup guards, HTTPS-only endpoints, bounded key downloads/refreshes, five-minute key caching,
  rotation/outage behavior, and HTTP503 for unavailable verification.
- Real signed-token tests, HTTP filter-chain/scope checks, configuration binding tests and a production-profile
  Kubernetes setting. See [identity operations](identity.md). Actual provider client configuration, live TLS/rotation
  drills and opaque-subject identity mapping remain environment work.

## Previously completed reliability and upload boundaries

- Durable, fenced outbox publication leases with no broker I/O inside the scheduled publisher's database transaction;
  bounded retries count crashes; expired attempts recover and stale completions cannot clobber a new claim. Additive V3
  migration, dead/leased gauges and dead-event alert are provided.
- Explicit Kafka dead-letter topic routing and propagation of failed dead-letter sends. Real Kafka delivery and
  failure-path tests accompany the change.
- Image type/decoder/dimension/byte validation before upload completion; MP4 initial-box admission; ETag-conditional
  validation/copy protects against staging replacement. Images are capped at10MiB/20MP; video remains100MiB. Two
  concurrent validations per app instance bound decoding work; saturation returns503 without queuing, leaving uploads
  retryable.
- Application enforcement of the1MiB API body limit before JSON deserialization, including requests without
  Content-Length. Binary uploads remain direct to S3.

## Next priorities

1. Media quarantine and malware scanning: durable processing jobs with leases, full video parsing/transcoding and
   moderation decisions before public access. Revalidate legacy completed media. Completion still performs bounded S3
   I/O inside its per-upload transaction; move that work to claimed background jobs while preserving idempotent
   finalization. Add object orphan reconciliation and retention policies.
2. Privacy lifecycle: audit access, administrative moderation permissions, consent-aware analytics and data-subject
   erasure, with policy-aware retention/tombstones that survive event replay. Do not simply delete outbox envelopes that
   Kafka consumers still reference.
3. Cache stampede control and feed efficiency: coalesce cache misses, jitter TTLs, batch session-item inserts and
   hydrate only enough candidates to fill a page. Measure large-follow graphs and replace full truth fallback with a
   candidate index before claiming scale.
4. Asynchronous impressions/engagement analytics with versioned event envelopes, bounded retries, consent checks,
   replay-safe aggregation and freshness/latency instrumentation. Current impressions remain synchronous durable
   records.
5. Worker separation and throughput: independently scale API, publication, consumption, fan-out and media roles; add
   measured bounded publication batches and job scheduling fairness. Current outbox publication is one send per100ms per
   replica. Add process-kill, rebalance, DB-timeout and partial broker/DLT outage drills.
6. Operational deployment: real OIDC/IAM/KMS, TLS/ACLs, managed HA data services, restore drills, Kubernetes rollout
   checks and controlled high-scale benchmarks. Multi-region privacy authority, fencing and regional failover remain
   architectural extensions.

No current local result verifies the prompt's billion-user scale, million-RPS target or availability SLO.
Recommendations, live feed updates, full video processing and multi-region operation remain incomplete.
