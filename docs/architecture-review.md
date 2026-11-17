# Architecture review

1. **Facebook feed for a billion users?** Separate authoritative graph/posts from viewer-sharded derived feeds, author
   recent-post indexes and region-local cache. Use hybrid candidate delivery, batch hydration and current authorization;
   this local baseline supplies correctness, not that capacity.

2. **Fan-out on write?** After durable publication, keyset-page followers and insert viewer/post entries in bounded
   checkpoint transactions. Reads are cheap but writes amplify by degree.

3. **Fan-out on read?** Join current follows to recent author posts in one set query, then rank bounded candidates.
   Avoid per-author SQL; very large graphs need sharded indexes and fan-in budgets.

4. **Why hybrid?** Push ordinary writers for read efficiency, pull celebrity/high-volume writers to avoid bursts.
   Threshold policy and gap coverage cost complexity and must follow measured economics.

5. **Celebrity handling?** Persist PULL mode on a post when follower count reaches configurable threshold. Emit one
   event and find it through author truth on reads; no follower expansion.

6. **Avoid amplification?** Bound push degree, restrict retention and eventually target active recipients. Never eagerly
   insert100M entries merely because broker throughput is high.

7. **Thousands of follows?** Set-based graph queries avoid N+1 but still scan too much at the tail. Add recent-author
   indexes, bounded per-source streams and candidate precomputation after measurement.

8. **Social graph storage?** Unique directed pairs with follower/author indexes; serialize conflicting edge actions
   using ordered user locks. Scale via sharded dual adjacency projections and authoritative privacy state.

9. **Partition posts/timelines?** Author-shard posts for profile/pull access, viewer-shard timelines for home reads, and
   explicitly budget scatter/gather for hydration. Baseline stays unpartitioned PostgreSQL.

10. **Unique post IDs?** UUIDv4 with database primary key; no clock coordination. Collisions are vanishingly unlikely,
    but constraints remain the integrity boundary.

11. **Kafka role?** Durable at-least-once event boundary with partition ordering by post ID and independently scalable
    job consumers. No ordering across partitions and no end-to-end exactly-once claim.

12. **Outbox prevents misses?** Commit source post and outbox in one DB transaction, then mark published only after
    broker acknowledgment. Crash between publish/mark can duplicate, never silently lose a committed event.

13. **Idempotent fan-out?** Post-keyed jobs and unique(viewer,post) entries make duplicate consumption safe. Consumers
    inspect current source truth instead of applying stale lifecycle payloads.

14. **Partial recovery?** Checkpoint UUID and entries commit together per500 followers under row lock. Crashes roll back
    a batch; resume from last committed follower.

15. **Deleted post filtering?** Current SQL state=PUBLISHED is mandatory even for old timeline/cache IDs. Tombstones
    remain until policy-aware purge; no stale ID can supply content itself.

16. **Blocks/private?** Bidirectional block predicate, author active flag, private account approved follows and per-post
    visibility on all retrieval routes. Muting is preference, not access denial.

17. **Redis benefit?** Caches session ID order to reduce session-item lookups with short TTL. Future post hashes would
    need version checks; current cache never stores authorization or post content.

18. **Redis failure?** GET falls back to PostgreSQL; quota-protected mutations503. Bound DB concurrency and shed excess
    load rather than accepting unlimited cache misses.

19. **Cache stampedes?** Short-lived bounded DB fallback currently permits duplicate misses. At scale use TTL jitter,
    per-key single-flight with deadlines and admission control; not currently implemented.

20. **Merge push/pull?** Build union of pushed and truth-backed recent IDs, deduplicate by UUID, rank then persist
    immutable positions. Truth fallback repairs newly followed users and mode changes.

21. **Deduplication?** HashSet UUID during candidate merge plus unique session/post and viewer/post constraints. Stable
    session cursor walks each position once.

22. **Ranking?** Normalized recency, saturated engagement and self feature with configurable weights totaling one.
    Snapshot ranking is explainable/versioned but lacks learned personalization, manipulation resistance and diversity
    constraints.

23. **Stable cursor?** Base64 session UUID/position bound to viewer,mode,profile scope and15min expiry. Snapshot ordered
    IDs prevent engagement-driven reshuffles.

24. **New posts on later pages?** They wait until refresh/new session. Deleted or revoked items disappear and cursor
    advances over them; no duplicates from new arrivals.

25. **Counter drift?** Read indexed source counts rather than asynchronous deltas. Future sharded counters need
    idempotent event IDs, reconciliation and a bounded eventual consistency contract.

26. **Viral posts?** Distribute reader keys, pull delivery and cache immutable metadata carefully; source row like lock
    remains a hotspot. Split/shard counters only after measuring contention.

27. **Media delivery?** Direct signed PUT, verified completion and immutable private object copy, short signed GET;
    thumbnails run asynchronously. Private CDN, malware scanning and large-video multipart/transcoding are further
    integrations.

28. **One million RPS?** Requires partitioned candidates/graph, batch cached hydration, edge routing, independent read
    pools and distributed load verification. Existing relational truth/session creation cannot be claimed to reach it.

29. **Reduce backlog?** Measure arrival vs processing capacity, pull expensive creators, fairly split ranges, scale
    workers only within DB budget. Catch-up=backlog/(capacity-arrival); no catch-up when denominator<=0.

30. **Freshness measurement?** Record post commit to durable eligible entry time, plus last visible eligible post age
    and outbox/job lag separately. Current backlog counters are not a full freshness histogram.

31. **Regional outage?** Fenced promotion of active-passive region with verified privacy watermark, then rebuild local
    timelines/cache. Test RPO/RTO; no automatic multi-region support shipped.

32. **Global privacy?** Authority reads or short fenced leases; fail closed if permissions uncertain during partition.
    Async replication alone cannot provide immediate revocation.

33. **Consistency?** Atomic source writes/outbox, serialized graph actions, unique likes, optimistic post versions;
    eventual materialization and immutable session ordering with current visibility. Signed media has30s residual
    validity.

34. **Benchmark correctly?** Use independently seeded identities, representative skew and cold/warm mixes; report
    offered/achieved/dropped work, errors, quantiles, lag and server resource saturation. Never extrapolate single-node
    smoke tests to global scale.

35. **Changes at100M DAU?** Shard viewer timelines and dual graph adjacency, separate author post indexes, bulk range
    fan-out, isolated worker pools, managed multi-AZ data, load-shed admission and regional authorization authority.
    Validate capacity with workload distributions before committing shard counts.
