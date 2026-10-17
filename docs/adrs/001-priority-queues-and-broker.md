# ADR-001: Priority Queues as Redis Sorted Sets inside the Care Queue Service

- **Status:** Approved
- **Date:** 2026-10-16
- **Deciders:** Team Three

## Context and Problem

In the legacy architecture, patients are queued in a single SQL Server table with FIFO logic. A patient with severe chest pain waits behind 200 patients with a mild cold. During the winter peak (500 triage requests/s, 20,000 waiting patients, about 500 doctors), the central database becomes a bottleneck and the system collapses. The new architecture needs prioritized queues with FIFO inside each level, where a patient can move between levels or leave without affecting the rest of the queue, and where doctors pull the next patient atomically.

## Considered Alternatives

- **Alternative 1: Single SQL Table with `ORDER BY priority, requestedAt`**
  Fixes the ordering but keeps the central database as the bottleneck, with lock contention at 500 requests/s.
- **Alternative 2: One RabbitMQ Queue per Priority Level**
  Native FIFO, but a broker queue only allows consuming from its head. Moving or cancelling a specific patient leaves stale messages, queue depth and position become inaccurate, and aging through TTL sends the patient to the end of the next queue, losing the original `requestedAt`.
- **Alternative 3: Kafka with One Topic per Priority**
  High throughput, but its append-only log cannot remove or reorder individual messages, with the same limitation as Alternative 2.
- **Alternative 4: Redis Sorted Sets (one per level) with RabbitMQ as Event Backbone**
  Sorted sets scored by the original request time, managed by the Care Queue Service. RabbitMQ transports domain events and absorbs bursts.

## Decision

Implement the three queues (HIGH, MEDIUM, LOW) as **Redis sorted sets** owned by the Care Queue Service, and keep **RabbitMQ** as the event broker between services.

- **Structure:** one sorted set per level, with `consultationId` as member and `requestedAt` (ms) as score. A hash maps each patient to their current queue and `triageVersion`, so a patient is in one queue only and stale events are discarded.
- **Enqueue:** the Care Queue Service consumes `PatientTriaged` from RabbitMQ (idempotently) and inserts the patient into the matching sorted set.
- **Claim:** a doctor pulls with an atomic script (`ZPOPMIN` on HIGH, then MEDIUM, then LOW). The patient moves to a claimed set with a lease, confirmed after `PatientAssigned` is committed to the outbox. Expired leases are requeued.
- **Moves and aging:** a Lua script checks `triageVersion`, then runs `ZREM` and `ZADD` keeping the original score. Aging uses the same move for patients in LOW beyond the threshold defined in `spec.md`.
- **Recovery:** consultations in `WAITING` are persisted in `consultation_db`, so Redis is a rebuildable index.

## Trade-offs

- **Positives (What we gain):**
  - **Clinical Prioritization:** a HIGH patient is attended first, regardless of how many mild cases wait.
  - **Native Queue Operations:** remove, move and rank a specific patient in O(log N) without touching the rest of the queue.
  - **Accurate FIFO and Aging:** keeping the original score preserves arrival order after reclassification.
  - **Atomicity:** Lua scripts prevent double assignment and double queueing.
  - **Performance and Observability:** 500 operations/s over 20,000 patients is easily handled, and depth, oldest wait and position are cheap to query.
- **Negatives (Risks / What we lose):**
  - **In-Memory Durability:** Redis can lose recent writes. *Mitigation:* Redis is rebuilt from `consultation_db`; AOF persistence and a replica with failover.
  - **Claim Not Atomic across Redis and the Database:** a crash after `ZPOPMIN` could lose an assignment. *Mitigation:* lease-based claim with a recovery job.
  - **Custom Lua Logic:** scripts must be maintained. *Mitigation:* a small set of versioned scripts covered by automated tests (200 LOW + 1 HIGH, concurrent claims, out-of-order events).
  - **Two Technologies to Operate:** Redis and RabbitMQ. *Mitigation:* clear separation of roles, replicated deployments and alerts on DLQ depth and Redis memory.
  - **Starvation in LOW:** *Mitigation:* the aging job.

## Final Justification

The core requirements are operations on a waiting list: take the oldest patient of the highest level, move a patient between levels without losing their arrival position, and remove one who leaves. Redis sorted sets provide these natively and atomically, while a broker queue only supports consuming from its head. Using RabbitMQ for events and Redis for the queues gives each tool the role it handles best, and persisting state in `consultation_db` keeps Redis disposable, which contains its main risk. This is worth the extra effort, since prioritized care is the Core Domain of MedConnect.