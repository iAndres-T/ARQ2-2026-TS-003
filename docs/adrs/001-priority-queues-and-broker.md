# ADR-001: Priority Queues as Separate Queues over a Message Broker

- **Status:** Approved
- **Date:** 2026-10-12
- **Deciders:** Team Three

## Context and Problem

In the legacy architecture, patients are queued in a single SQL Server table with FIFO logic. A patient reporting severe chest pain is placed behind 200 patients with a mild cold and waits for hours. During the winter peak (up to 500 triage requests/s and 20,000 waiting patients, with only about 500 doctors on shift), the central database becomes a bottleneck and the system collapses. The new architecture needs a queuing mechanism that prioritizes patients by clinical criticality, absorbs traffic bursts asynchronously, and lets available doctors pull the next patient while respecting priority order.

## Considered Alternatives

- **Alternative 1: Single SQL Table with `ORDER BY priority, requestedAt`**
  Keeps the queue in the central database and adds a priority column. Fixes the ordering but keeps the database as the bottleneck, with lock contention and write pressure at 500 requests/s.
- **Alternative 2: Single Broker Queue with Native Message Priorities**
  Uses one queue where each message carries a priority value. Priority handling is approximate, a patient cannot be easily moved between priorities once enqueued, and queue depth per level is hard to inspect.
- **Alternative 3: Kafka with One Topic per Priority**
  Offers very high throughput, but its append-only log does not support removing or reordering individual messages. Moving a patient on reclassification, or removing one who cancels, is awkward.
- **Alternative 4: Redis Only**
  Very fast and simple for sorted structures, but weaker durability guarantees for clinical data unless carefully configured, and it lacks native retry and dead-letter handling.
- **Alternative 5: Message Broker with One Durable Queue per Priority Level (HIGH, MEDIUM, LOW)**
  Uses a broker (e.g., RabbitMQ) with three separate durable queues, strict-priority pull consumption and FIFO inside each queue.

## Decision

Adopt a **message broker with one durable queue per priority level (HIGH, MEDIUM, LOW)** and a pull-based consumption model implemented in the Care Queue Service.

- **Triage Publication:** After the rule-based Triage Engine assigns a priority, the patient is published to the queue matching that level.
- **Strict-Priority Consumption:** An available doctor pulls the next patient from HIGH first, then MEDIUM, then LOW. Within each queue, order is FIFO by the original request timestamp.
- **Atomic Reclassification:** When symptoms are updated or medical staff overrides the priority, the patient is moved atomically to the new queue, so a patient is never in two queues at once. Stale events are discarded by comparing `triageVersion`.
- **Reliable Messaging:** Producers use the transactional outbox pattern and consumers are idempotent. Failed messages are retried and sent to dead-letter queues.
- **Broker Technology:** RabbitMQ as the reference choice (TBD: final confirmation by the team).

## Trade-offs

- **Positives (What we gain):**
  - **Clinical Prioritization:** Critical patients are never blocked behind mild cases; a HIGH patient is attended by the first available doctor.
  - **Burst Absorption:** The broker levels the load during peaks, and stateless triage consumers scale horizontally with queue depth.
  - **Decoupling from the Central Database:** The queue no longer depends on SQL Server, removing the lock contention and write bottleneck.
  - **Observability:** Per-level metrics (queue depth, oldest waiting time, consumer lag) are straightforward to collect.
- **Negatives (Risks / What we lose):**
  - **Custom Consumption Logic:** Strict-priority pulling is not native to the broker and must be implemented in the Care Queue Service. *Mitigation:* encapsulate it in a single claim operation covered by automated tests (e.g., 200 LOW + 1 HIGH patients).
  - **Atomic Moves Between Queues:** Reclassification requires removing and inserting in one operation. *Mitigation:* use an atomic operation (transaction or script) together with `triageVersion` checks and idempotent consumers.
  - **Operational Complexity:** Running a broker adds infrastructure to manage. *Mitigation:* durable queues, publisher confirms, DLQ alerts and replicated broker nodes.
  - **Eventual Consistency:** The consultation state reflects queue changes shortly after they happen, not instantaneously. *Mitigation:* the Consultation Service is the source of truth and updates its state from queue events.

## Final Justification

Separate priority queues over a message broker are the best option for this case because they solve both failures of the legacy system at once: the incorrect ordering (a heart attack waiting behind mild colds) and the collapse of the central database under 500 requests/s. They keep the clinical rule explicit (HIGH before MEDIUM before LOW, FIFO inside each level), allow a patient to change queues safely when the situation changes, and scale independently of the rest of the platform. The extra implementation effort in the claim logic is a worthwhile cost, since this is the Core Domain of MedConnect.