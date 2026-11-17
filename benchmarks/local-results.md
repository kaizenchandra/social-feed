# Local measurements — 2026-11-14

These are development measurements, not a capacity or availability certification. One 30-second run offered 20
chronological **new-session builds** per second through authenticated HTTP. It completed 600 requests at 19.9994 RPS,
with zero HTTP errors, zero failed response checks and zero dropped iterations. Client-observed latency: p50 15.623 ms,
p95 23.670 ms, p99 30.06 ms, max 38.022 ms. The p99 value was printed by the k6 threshold evaluation; the original JSON
export contains p50/p95 but not p99. Future exports explicitly include p99.

Environment: macOS arm64 host, OrbStack Docker 29.8.2 (about 9 GB daemon memory), k6 2.4.0 on the same host, one Docker
Compose Java 21 application replica at `http://localhost:28080`, PostgreSQL 17, Redis 7.4, Kafka 3.9.1 and LocalStack
4.10.0. The application image was rebuilt from the updated source using the delivered multi-stage Dockerfile. Other
development applications shared the host; CPU, disk, GC, cache warmth and background contention were not controlled.
This is not a hardware-isolated benchmark.

The API seeder added 20 public users, five followed authors per user and five public posts per user (100 posts), using
fixed-seed Zipf-like weighted sampling without replacement. The database also retained earlier smoke-test data. It does
not represent millions of followers, celebrity routing at the default 10,000-follower threshold, deep pagination or
large post histories. Every feed iteration requested up to 20 items and checked HTTP 200; integration tests separately
verify feed correctness.

Reproduce using the [benchmark commands](README.md). Raw exports and the credential-bearing fixture are under ignored
`benchmarks/results/`. Feed generation used `RPS=20 DURATION=30s VUS=20 MAX_VUS=100`, chronological mode. The initial
run preceded adding explicit p99 summary statistics and the aggregate checks threshold; its 600 individual response
checks all passed.

Five additional **script validation runs**, each at five iterations/sec for three seconds, completed with no errors,
failed checks or dropped iterations:

| Operation                    | HTTP requests | Client p99 (ms) |
|------------------------------|--------------:|----------------:|
| Post publication             |            16 |          30.023 |
| Same-key publication retries |            16 |          19.084 |
| Post lookup + follower graph |            32 |          14.822 |
| Hot-post like/unlike         |            15 |          54.832 |
| One-item cursor pages        |            16 |          18.737 |

The short run length makes these operation percentiles unsuitable for performance conclusions. Requests near the
scheduling boundary explain 15 versus 16 iterations. These checks verify that the supplied workloads execute; they are
not sustained throughput tests. No 1,000/10,000/100,000/1,000,000-RPS run, Redis/Kafka outage benchmark, fan-out
throughput measurement, availability measurement or regional failover drill was executed. All such targets remain
unverified.
