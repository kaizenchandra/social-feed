# Reproducible performance protocol

A small local workload has been measured: see [results and limits](local-results.md). Target-scale RPS and SLOs remain
unverified.

From `social-feed/`, seed a disposable development deployment using its development JWT secret:

```sh
export BASE_URL=http://localhost:28080
export JWT_SECRET=local-verification-secret-at-least-32-characters
python3 benchmarks/seed.py --users 20 --follows 5 --posts 5 --distribution zipf
k6 run --summary-export benchmarks/results/feed-local.json -e BASE_URL="$BASE_URL" -e FIXTURE=results/fixture.json -e RPS=20 -e DURATION=30s -e VUS=20 -e MAX_VUS=100 benchmarks/feed.js
k6 run -e BASE_URL="$BASE_URL" -e FIXTURE=results/fixture.json -e WORKLOAD=pages benchmarks/operations.js
```

Fixture paths are relative to the k6 script. Fixtures contain short-lived development credentials, are mode 0600 and are
ignored under `results/`. Seed creates new users on every invocation; use a disposable database. The seeder uses fixed
random seed 42 and weighted sampling without replacement for a small Zipf-like graph; it does not claim to reproduce the
1%/50% distribution below.

`operations.js` supports `WORKLOAD=posts|retries|graph|likes|pages`. These isolate publication/outbox writes,
identical-key retries, follower graph reads, hot-post engagement, and session continuation/cache reads. Set a unique
`RUN_ID` for each independent post-writing run. Graph performs two HTTP calls per iteration; report HTTP RPS separately
from offered iteration rate. Pages cycles sessions and therefore includes some new-session builds. Use `MODE=ranked` or
`pull` with `feed.js` to compare ranking and pull; fan-out rate/backlog must be read from server metrics, not inferred
from HTTP completion. Supply sufficient users for quotas, VUs and generator capacity for larger runs.

Run `k6 run -e RPS=1000 -e TOKENS='["..."]' benchmarks/feed.js`; repeat with 10000, 100000, 1000000 only with sufficient
distributed generators, seeded users and server capacity. Capture `--summary-export results/run.json` and time-series
output to Prometheus. Never count dropped iterations as achieved traffic. Quota is 100 requests/sec/user: provision
enough distinct identities. Pin infrastructure sizes, git revision, DB statistics, JVM flags, cache warmth and generator
CPU. Report offered and achieved RPS, errors, p50/p95/p99 and dropped iterations together.

Seed graphs via application APIs; test uniform (200 follows) and Zipf distributions (1% creators receive 50% edges),
celebrity 100M edges on a suitably sharded environment, and 100K-following readers. Separate cold session builds from
cursor page retrieval; current script deliberately stresses builds. Record fan-out entries/sec and source publication
age, outbox/job backlog, DB query plans, CPU, disk, heap/GC, Redis memory, cache hit ratio and Kafka group lag.
Benchmark post creation with unique idempotency keys, repeated-key retries, like hot spots and media bandwidth
separately. Use broker/Redis stop-start fault injection only on disposable environments; compare freshness recovery with
`backlog/(capacity-arrival rate)`.

This single PostgreSQL baseline cannot plausibly serve 1M RPS without changing candidate retrieval and sharding. Do not
use this script as evidence of capacity until executed and reconciled against server metrics.
