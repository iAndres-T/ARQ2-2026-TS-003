# ADR-006: PostgreSQL per Service as the Relational Store, with Redis for the Queues

- **Status:** Approved
- **Date:** 2026-10-28
- **Deciders:** Team Three

## Context and Problem

The legacy system collapsed because a single shared SQL Server coupled every module: one lock, one resource pool and one failure domain for triage, queueing, consultations and prescriptions. The redesigned services need to own their data, and several of them have strict data requirements. Producers must save a state change and its event in the same transaction (transactional outbox, ADR-002), consumers must discard duplicate events, scheduling must serialize out-of-order events for the same consultation, and the audit trail must be append-only. ADR-001 already justifies Redis for the waiting queues; this ADR decides the datastore for everything else and how it is deployed.

## Considered Alternatives

- **Alternative 1: One Shared PostgreSQL Instance with a Schema or Database per Service**
  Cheaper to run, but one instance is still a shared failure and resource domain. A heavy query, a full disk or a crash in one service would affect all the others, which is the exact coupling that collapsed the legacy system.
- **Alternative 2: MongoDB**
  Multi-document transactions exist, but the outbox, the unique-key deduplication, the `SKIP LOCKED` relay and the advisory locks are simpler in PostgreSQL, implemented with plain transactions, unique keys and built-in locking. The document-shaped audit payload is covered by `jsonb`.
- **Alternative 3: Cassandra**
  It has no multi-row transactions or row locks, which the outbox and the consultation state machine need. It is tuned for write-heavy wide rows, which is not our workload.
- **Alternative 4: MySQL**
  Viable for transactions and row locks. Rejected because PostgreSQL offers `jsonb`, advisory locks and partial indexes out of the box, and the team knows it better.
- **Alternative 5: One Dedicated PostgreSQL Instance per Service**
  Each service gets its own database server, volume and credentials, keeping the relational guarantees and the isolation.

## Decision

Use **PostgreSQL 16** with **one dedicated instance per service** (`postgres:16-alpine`), and keep **Redis** for the care queues (ADR-001), the consultation read cache and the API Gateway rate-limit counters. Redis is never the system of record for any service, with one exception recorded in ADR-007: the care-queue outbox, leases, claim idempotency records and doctor projection live only in Redis.

- **Instances:** `identity-db`, `consultation-db`, `triage-db`, `scheduling-db`, `prescription-db`, `audit-db` and `recording-db`. Each has its own volume, credentials and resource limits (256M memory and 0.5 CPU per container). No service reads another service's tables.
- **Care Queue Service:** has no relational database; its state lives in Redis only (ADR-001), rebuildable from `consultation_db`.
- **Schema management:** Flyway migrations per service, plus shared common migrations for the outbox, inbox and idempotency tables.
- **Outbox and inbox:** the state change and the event row are written in one ACID transaction (ADR-002). The relay reads pending rows with `SELECT ... FOR UPDATE SKIP LOCKED`, so several instances can run without publishing twice. Consumers deduplicate with a unique `(event_id, consumer)` key.
- **Audit:** `audit_db` is append-only, enforced by triggers and a restricted role (created by the `audit-db` init script, not by Flyway) with `INSERT` and `SELECT` only. Event payloads are stored as `jsonb`.
- **Scheduling:** row locks plus a per-consultation advisory lock serialize out-of-order events.
- **Recording:** `recording-db` is a separate container, which is the bulkhead of ADR-003.
- **Cross-service data:** no cross-service queries; services exchange events and keep local projections (ADR-005). The one sanctioned synchronous read is care-queue-service calling consultation-service's internal snapshot (`GET /internal/v1/consultations/waiting`, `X-Internal-Token`) to rebuild Redis; it goes through consultation-service's API, never its tables.

## Trade-offs

- **Positives (What we gain):**
  - **Failure and Resource Isolation:** an incident in one database cannot take down the others, unlike the legacy shared server.
  - **Atomic Outbox:** state and event commit together, so no event is lost or invented.
  - **Safe Concurrency:** `SKIP LOCKED`, unique keys, row locks and advisory locks are built in, with no extra machinery.
  - **Flexible and Tamper-Resistant Audit:** `jsonb` stores any event shape, while triggers and a restricted role prevent updates and deletes.
  - **Independent Evolution:** each service migrates and scales its own schema and database.
- **Negatives (Risks / What we lose):**
  - **Memory Footprint:** seven PostgreSQL containers are heavy on a local machine. *Mitigation:* a 256M memory and 0.5 CPU limit per container.
  - **Operational Overhead:** more instances to back up and monitor. *Mitigation:* identical image and configuration for all of them, and Flyway for uniform migrations.
  - **No Cross-Service Queries:** reports that join data from several services are impossible by design. *Mitigation:* events and local projections (ADR-005).
  - **Not Specialized for Wide-Column or Document Workloads:** we give up the horizontal write scaling of Cassandra or MongoDB. *Mitigation:* the waiting-list load lives in Redis, and the remaining services are well within a single instance's capacity.

## Final Justification

The legacy failure came from sharing one database, so the data must be isolated per service, not just per schema. PostgreSQL covers every need with built-in features: ACID transactions for the outbox, `SKIP LOCKED` for the relay, unique keys for deduplication, locks for ordering and `jsonb` for audit payloads. MongoDB, Cassandra and MySQL could do part of this only with extra machinery or less fitting features. Redis stays where its data structures matter (ADR-001). The cost is more containers and no cross-service queries, which we accept because it matches the event-driven design.
