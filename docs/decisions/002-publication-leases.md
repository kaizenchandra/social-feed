# ADR 002 — Lease outbox publication outside database transactions

Status: implemented. The original publisher held an outbox row lock and a pooled database connection while waiting up to
six seconds for a broker acknowledgment. Broker outages could consume connection capacity and couple feed workers to
external latency.

Claim one due event using `FOR UPDATE SKIP LOCKED` in a short transaction, persist a random token, a 30-second expiry
and the incremented attempt count, then commit. Send the event ID to Kafka outside that transaction. A second short
transaction checkpoints success or failure only if the token still matches and the lease has not expired. The
independent transaction template prevents claim/checkpoint work from accidentally joining another transaction. Scheduled
publication itself has no surrounding transaction.

The lease exceeds normal configured producer blocking (2 seconds) plus acknowledgment wait (6 seconds). Process stalls
can exceed any lease; expiry permits another publisher to reclaim the event. A stale publisher cannot overwrite that
publisher's claim. Sends may still duplicate if the broker accepted a message before a crash, timeout or lease expiry.
Consumers therefore remain idempotent and operate on current post truth. No exactly-once or strict cross-publisher
ordering guarantee is claimed.

Attempts increment at claim, so crashes consume retry budget as well as explicit failures. Ten attempts exhaust the
budget; expired exhausted claims are marked dead in batches of at most 100. Normal failures release the claim and back
off exponentially from two seconds, capped at 300 seconds. Dead events require investigation and explicit requeue.
`feed.outbox.backlog` excludes dead events; `feed.outbox.dead` and `feed.outbox.leased` expose exhausted and in-flight
work separately, sampled every cleanup interval (60 seconds).

The scheduler polls at 100 ms with one event per invocation per replica. This deliberately bounds in-flight work; it is
not sufficient for the prompt's target post rate. Introduce measured bounded batching/parallel publication or CDC before
high-scale rollout. Four scheduler threads prevent one slow publication from starving fan-out, cleanup and thumbnail
scheduling; they do not create parallel invocations of one scheduled task within an instance.

The dead-letter resolver explicitly targets `feed.events.DLT` with the source partition, matching the declared
12-partition topic. A failed DLT send throws, allowing the listener error handler to retry rather than reporting
successful recovery. Preserve original values, keys and Kafka exception/original-record headers for investigation.
Verify topic replication, ACLs and retention before production use.

V3 is an additive Flyway migration. Old publishers do not honor leases, so avoid mixed old/new worker versions during
rollout (pause workers, migrate, deploy new workers, resume). Retain outbox envelopes for the supported Kafka replay
window. This change does not implement archival or deletion of those envelopes.

Executed tests cover network send without a transaction/row lock, concurrent claims, expired-lease takeover, stale-token
rejection, ten crashed attempts, failed broker publication, failed DLT publication and a real Kafka DLT round trip. See
the verification record for commands and results.
