# ADR-007: Redis Outbox inside the Lua Scripts for the Care Queue Service

- **Status:** Approved
- **Date:** 2026-10-29
- **Deciders:** Team Three

## Context and Problem

ADR-002 requires every producer to save its state change and its event "within a single atomic transaction in its local database", and ADR-006 states that "Redis is never the system of record for any service". The Care Queue Service has no relational database: its state (the HIGH/MEDIUM/LOW sorted sets, the entry hashes, the claim leases and the doctor projection) lives in Redis (ADR-001). It still produces events that the rest of the platform depends on: `PatientEnqueued` (after every enqueue, move or aging promotion) and `PatientAssigned` (after a confirmed claim). The event must be recorded if and only if the Redis change happened, and a crash between "change Redis" and "publish to RabbitMQ" must not lose or invent an event.

## Considered Alternatives

- **Alternative 1: Publish Directly to RabbitMQ after the Lua Script**
  Simple, but a crash or broker outage between the script and the publish loses the event (a patient moved to HIGH but nobody is told), and publishing first can announce a move that never happened. This is exactly the dual-write problem ADR-002 rules out.
- **Alternative 2: A PostgreSQL Database only for the Outbox**
  Keeps the ADR-002 wording, but the state change (Redis) and the outbox row (PostgreSQL) are again two stores with no shared transaction, so the dual-write problem only moves. It also adds an eighth database container.
- **Alternative 3: Redis Streams as Outbox**
  Atomic with the Lua script as well, but a list is enough for a single relay with at-least-once semantics, and consumer groups add machinery the team does not need.
- **Alternative 4: Redis List Written inside the Same Lua Script**
  The script that changes the queues also pushes the serialized event envelope to a Redis list. Redis runs a Lua script atomically, so state and event are committed together.

## Decision

Use a **Redis list `outbox:pending` written inside the same Lua script** that changes the queue state. This is the care-queue equivalent of the ADR-002 outbox.

- **Atomic write:** `enqueue_or_move.lua`, `confirm.lua` and `requeue_expired.lua` change the sorted sets / hashes and `RPUSH` the event envelope (with `eventId`, `aggregateVersion`, `correlationId` and `traceparent`) in one script. Inbox keys for consumed events are set in the same script, so deduplication is atomic too.
- **Relay:** `RedisOutboxRelay` moves records from `outbox:pending` to `outbox:inflight` (`LMOVE`), publishes them persistent and mandatory with publisher confirms, then removes them (`LREM`). A Redis lock key with a TTL keeps one relay active across instances; each run first returns inflight leftovers to pending. Unroutable records go back to pending with backoff; nothing is dropped. A run drains up to 20 batches of 100.
- **Delivery:** at-least-once, as in ADR-002. A crash after the broker confirm and before `LREM` republishes the record; consumers deduplicate by `eventId` and compare `triageVersion` / `aggregateVersion`.
- **Durability:** Redis runs with AOF (`appendonly yes`, default `appendfsync everysec`) and `maxmemory 300mb` with `noeviction`, so outbox records, leases and inbox keys are never evicted; when memory is full, writes fail instead of silently losing state. A Prometheus alert fires above 80 % of `maxmemory`.
- **Rebuild:** after a Redis loss, `QueueRebuilder` reloads the `WAITING` consultations from consultation-service's internal snapshot and replays them through `enqueue_or_move.lua` without emitting events.

## Trade-offs

- **Positives (What we gain):**
  - **True Atomicity:** state change and event are committed by one script; no dual write.
  - **No Extra Store:** no eighth database just for an outbox, and no second network hop on the claim path.
  - **Same Guarantees as ADR-002 for Consumers:** persistent, mandatory, confirmed publishes; at-least-once; `eventId` deduplication.
- **Negatives (Risks / What we lose):**
  - **Loss Window on Redis Failure:** with `appendfsync everysec` up to about one second of writes can be lost on a crash, and losing the volume loses all unpublished records. There is no replica or failover. *Mitigation:* AOF, the rebuild from `consultation_db`, and the platform's recovery paths below.
  - **Not Rebuildable State:** unpublished events, leases, idempotency records and the doctor projection cannot be derived from `consultation_db`. *Mitigation:* a lost `PatientAssigned` leaves the consultation `WAITING` in consultation-service, so the rebuild re-enqueues it and another claim succeeds; doctors re-announce themselves with `PUT /doctors/me/availability FREE`, which always re-emits `DoctorBecameAvailable`. Consultations whose `PatientEnqueued` was lost stay `TRIAGED` and are not part of the `WAITING` snapshot: a known gap.
  - **Memory Bound:** the outbox competes with the queues for memory. *Mitigation:* `noeviction`, the memory alert, inbox keys with a 6 h TTL.
  - **Single-Node Scripts:** the scripts build keys internally, so they require a single Redis node (no Redis Cluster).

## Final Justification

The care queue already depends on Redis for its core operations, and Lua scripts are Redis's unit of atomicity. Writing the event inside the same script is the only option that gives the ADR-002 guarantee (event if and only if state change) without introducing a second store and a new dual write. The price is that Redis becomes the system of record for a small amount of state, protected by AOF and `noeviction` but not by a replica; we accept that for this project and record the replica as a known limitation.
