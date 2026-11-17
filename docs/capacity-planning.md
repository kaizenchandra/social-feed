# Capacity estimates, not measured results

Assumptions: 1B registered, 100M DAU, 10M peak concurrent (not all issue requests each second); 1 post/DAU/day =100M
posts/day; 100 feed opens/DAU/day=10B requests/day; 20 results and 200 candidates/read; peak multiplier posts 5, reads
8.64. Average follows=200, hence 200B directed edges across registered users. 1% creators attract 50% of edges; ordinary
eligible fan-out assumed 100, 90% of posts push and 30% of recipients active. These are workload hypotheses requiring
measured degree/activity distributions.

| Dimension             | Formula and estimate                                                                                                                                                                |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Mean post writes      | 100M/86400 =1,157/s; peak 5,787/s                                                                                                                                                   |
| Mean feed reads       | 10B/86400 =115,741/s; peak 1M/s                                                                                                                                                     |
| Result hydration      | peak 1M *20 =20M posts/s                                                                                                                                                            |
| Candidate reads       | peak 1M *200 =200M IDs/s; baseline snapshots up to1000 are costlier                                                                                                                 |
| Push amplification    | mean 1157*.9*100 =104,167 entries/s; active-only hypothetical 31,250/s                                                                                                              |
| Outbox Kafka          | ~1157 post events/s mean, peak5787 plus edits/deletes; batching does not create one Kafka message/recipient                                                                         |
| Timeline growth       | 9B entries/day *80B =720GB/day raw, seven days5.04TB, x3 replicas15.12TB excluding index overhead                                                                                   |
| Metadata              | 100M/day *1KB =100GB/day,36.5TB/year raw,109.5TB x3                                                                                                                                 |
| Graph                 | 200B edges *40B=8TB raw, two adjacency indexes + replication may exceed48TB                                                                                                         |
| Redis                 | 10M active *1000 IDs *40B=400GB minimum, 2x allocation=800GB primary; 15min concurrent sessions can add more                                                                        |
| Media                 | assume80% images (2MB),10% video(20MB),10% text =>360TB/day,131.4PB/year before thumbnails/transcodes/replicas                                                                      |
| Media egress          | assume20% of20 items/open fetch2MB =>10B*8MB=80PB/day,~7.4Tb/s average; CDN hit ratio affects origin traffic                                                                        |
| Metadata bandwidth    | mean115741*20KB=2.31GB/s (~18.5Gb/s); peak20GB/s (~160Gb/s)                                                                                                                         |
| DB shards             | target<10K durable inserts/s/shard measured capacity hypothetical: ceil(520833 peak entries/s /10000)=53 timeline shards before headroom; use>106 for2x margin only after benchmark |
| Workers               | hypothetical5000 entries/s/worker: ceil(520833/5000)=105 workers,210 with2x headroom; baseline one batch/100ms/instance is not that measured rate                                   |
| Backlog recovery      | 1B queued / (1M capacity - .52M arrival) =2083s ~35min; if capacity<=arrival there is no recovery                                                                                   |
| Cross-region metadata | x2 remote copies of post metadata=200GB/day plus graph mutation logs; media replication ~720TB/day for two full copies, locality/lifecycle policies essential                       |

Mean follower count hides heavy tails and recipient activity. A single100M-follower event overwhelms seconds of ordinary
work. Store degree quantiles, posting-rate/degree correlation, active recipient fraction, burst sizes and per-creator
queue cost. Pull celebrities, use job fairness and adaptive budgets. Network/storage numbers distinguish metadata from
media and delivered entries from Kafka events. Million RPS and 99.99% are goals; no local test proves them. PostgreSQL
source joins and synchronous session inserts are the first scaling bottlenecks. Before100M DAU: viewer-sharded
timelines, author recent-post indexes, batch hydration cache, independent worker deployment, bulk inserts, admission
control and region-aware privacy authority are required.
