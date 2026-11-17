# ADR 001: modular hybrid with a truth-backed baseline

Status: accepted for local implementation; capacity unverified.

| Model  | Read cost                           | Write cost                   | Failure and freshness                                    |
|--------|-------------------------------------|------------------------------|----------------------------------------------------------|
| Push   | cheap timeline IDs, still authorize | one insert/eligible follower | durable backlog; expensive celebrity bursts              |
| Pull   | graph join plus candidate scan      | one post/outbox              | current truth; heavy readers and large graphs hurt tails |
| Hybrid | merge/deduplicate, authorize        | ordinary creators push       | more operational state, threshold transition gaps        |

First establish pull correctness. Add push as an optimization, then route new posts using a configurable follower
threshold (10K default). Policy freezes delivery on each post, so later threshold changes cannot strand historical
posts. The read path merges current eligible truth candidates with pushed IDs by stable UUID; it does not trust a
creator's *current* mode to find old posts. Truth fallback covers new follows and workers delayed or unavailable. This
deliberate baseline is heavier than a pure timeline service.

For a 100M-follower creator, a single push post means 100M inserts, ~8GB raw entries at 80B each before
indexes/replicas, and at 50K entries/sec >33min exclusive processing. Route PULL: one event and zero follower inserts.
Kafka uses post ID keys, so a celebrity's post does not create 100M broker messages. Workers batch PostgreSQL followers
500 at a time. At larger scale partition follower ranges into separately leased jobs, with quotas so ordinary writers
cannot suffer priority inversion.

A static threshold misses post rate, active follower fraction and queue utilization. The next policy should estimate
expected fan-out cost and queue budget using hysteresis. Never change a creator mode without historical coverage. Pure
pull can be cheaper for low activity or huge follow churn. Kafka is included because the requested platform has an
explicit independently replayable event boundary; for a small deployment PostgreSQL jobs alone would be simpler.
