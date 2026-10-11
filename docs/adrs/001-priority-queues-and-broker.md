# ADR-001: Priority Queues as Separate Queues over a Message Broker

- **Status:** Draft
- **Date:** 2026-10-10
- **Deciders:** Team

## Context

The legacy system queues patients in a single SQL Server table with FIFO logic. A patient with chest pain waits behind 200 mild cases, and 500 triage requests/s collapse the central database. The new architecture needs prioritized, asynchronous queues that absorb bursts and let doctors pull patients by criticality.

## Decision

Use a message broker with **one durable queue per priority level (HIGH, MEDIUM, LOW)**. Doctors pull with strict-priority consumption (HIGH, then MEDIUM, then LOW). FIFO order is preserved inside each queue.

- Broker: RabbitMQ (TBD: final confirmation).
- Triage publishes to the queue matching the assigned priority.
- Reclassification or override moves the patient between queues atomically (never in two queues).
- Producers use a transactional outbox. Consumers are idempotent.

## Alternatives Considered

| Option | Why not (draft) |
|---|---|
| Single table with `ORDER BY priority, requestedAt` | Keeps the central DB as bottleneck and lock contention at 500 req/s |
| Single broker queue with native message priorities | Priority is approximate, hard to move a patient between priorities, hard to inspect queue depth per level |
| Kafka (topic per priority) | Strong throughput, but no per-message removal or reordering; moving or cancelling a waiting patient is awkward |
| Redis only | Fast, but weaker durability guarantees for clinical data unless carefully configured |

## Consequences

- (+) Critical patients never wait behind mild cases.
- (+) The broker absorbs bursts (load leveling); triage consumers scale horizontally.
- (+) Per-level metrics (depth, oldest wait) are straightforward.
- (−) Pull consumption and strict-priority logic must be implemented in care-queue-service.
- (−) Moving a patient between queues needs an atomic operation and version checks (`triageVersion`).