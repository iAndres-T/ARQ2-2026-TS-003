# ADR 004: Doctor Pull Consumption with Strict Priority

- **Status:** Draft
- **Date:** 2026-10-12
- **Deciders:** Team

- **Context and Problem:** In the legacy system, patients wait in a single FIFO table. A patient with chest pain can wait behind 200 mild cases, and every doctor competes for the same rows in the central database. We need a way for an available doctor to receive the next patient that:
  - always serves the most critical first (HIGH, then MEDIUM, then LOW);
  - keeps arrival order inside each level;
  - never gives the same patient to two doctors;
  - keeps each patient in only one queue;
  - does not affect triage, scheduling or recordings.

- **Considered Alternatives:**
  - **Alternative 1 – Push patients to doctors through RabbitMQ:** the broker cannot guarantee HIGH-first across three queues, and it sends patients to doctors who may be busy or offline.
  - **Alternative 2 – Pull from one RabbitMQ queue per priority:** keeps the strict order, but a waiting patient cannot be removed or moved (cancellation, priority override), and a moved patient loses their place in line.
  - **Alternative 3 – SQL table with row locking (PostgreSQL `SKIP LOCKED`):** correct and durable, but it puts constant load on a database and repeats the pattern that failed in the legacy system.

- **Decision:** Doctors **pull** their next patient, using **Redis sorted sets** and **one atomic claim**.
  - When a doctor is free, the app asks `care-queue-service` for the next patient (`POST /queue/claims`).
  - The waiting lines are three Redis sorted sets (HIGH, MEDIUM, LOW), ordered by the patient's original request time.
  - One atomic Redis script checks HIGH first, then MEDIUM, then LOW, takes the oldest patient and assigns them to the doctor in a single step. Two doctors can never get the same patient.
  - If the doctor repeats the request (retry), the same `Idempotency-Key` returns the same patient.
  - `consultation-service` confirms the change `WAITING → ASSIGNED`. If the patient does not connect in time (initially 120 s), the consultation becomes `NO_SHOW` and the doctor is free again.
  - LOW patients who wait too long (proposal: 30 minutes) move to MEDIUM so they are not forgotten. They never move to HIGH.
  - Every patient is also saved in `queue_db`, so the queues can be rebuilt if Redis fails. RabbitMQ keeps carrying the events between services.

- **Trade-offs:**
  - **Positives (What we gain):**
    - Critical patients are always served first (scenario S-F4-01).
    - One patient goes to exactly one doctor, without complex locks (scenario S-F4-02).
    - Fast even with 20,000 patients waiting.
    - Cancellations and priority changes are simple: the patient is never in two queues and keeps their place by arrival time.
    - Doctors only receive a patient when they are ready.
    - A failure in recordings or pharmacy cannot affect this flow.
  - **Negatives (Risks / What we lose):**
    - *Redis can lose the last second of data in a crash.* Mitigation: rebuild from `queue_db`, and `consultation-service` double-checks every assignment.
    - *The custom script needs careful testing.* Mitigation: tests for both scenarios, including two doctors claiming at the same time.
    - *Strict priority can leave LOW patients waiting too long.* Mitigation: the aging rule above.
    - *A single Redis node is a single point of failure.* Mitigation: add a replica; meanwhile claims answer `503` and doctors retry, while triage keeps working.
    - *It refines ADR-001* (waiting lines live in Redis instead of broker queues). Mitigation: update ADR-001 to match.

- **Final Justification:** This option is the only one that guarantees, at the same time, strict priority, arrival order, one doctor per patient and isolation from the other services, with moderate complexity. It directly removes the failure of the case: a patient with a heart attack can never be served after a mild case that arrived earlier. It also matches `spec.md`, which already assigns `care-queue-service` to Redis plus `queue_db`.
