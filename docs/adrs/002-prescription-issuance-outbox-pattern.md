# ADR-002: Asynchronous Integration via Transactional Outbox Pattern for Domain Events and Prescription Issuance

- **Status:** Approved
- **Date:** 2026-10-12
- **Deciders:** Team Three

#### Context and Problem
Inter-service domain events (e.g., `ConsultationRequested`) and third-party pharmacy prescription issuance share the same architectural risks under 500 req/s loads:
- **Availability Coupling:** Latency or downtime in third-party pharmacies blocks clinical workflows in MedConnect.
- **Double-Write Failure:** Persisting data and publishing to the network in the same thread can cause lost or duplicate events/prescriptions if network calls fail.

#### Considered Alternatives
- **Alternative 1: Synchronous REST (Direct HTTP Call):** Rejected due to tight availability coupling and latency amplification.
- **Alternative 2: Direct Broker Publishing in API Thread:** Rejected because transient network failures introduce double-write inconsistencies.
- **Alternative 3: Transactional Outbox Pattern:** Persists state and event records in the same DB transaction; a background worker publishes them to the broker.

#### Decision
Adopt the **Transactional Outbox** pattern across all event-publishing services and prescription delivery workflows.

- **Outbox per Service:** Each service saves its state change and event record within a **single atomic transaction** in its local database.
- **Outbox Relay:** A background worker polls the `outbox` table (`SELECT ... FOR UPDATE SKIP LOCKED`), publishes to the broker, and marks records as processed. Guarantees **at-least-once delivery**.
- **Idempotent Consumers:** Use unique keys (`prescriptionId`, `eventId`) to discard duplicate messages.
- **Pharmacy Dispatch:** The *Prescription Service* emits `PrescriptionIssued`. A dedicated *Pharmacy Dispatcher* consumes it and delivers it asynchronously.
- **Failure Handling:** Exponential backoff retries routed to a dead-letter queue (`prescription.pharmacy.dlq`) upon exhaustion.

#### Trade-offs
- **Positives:** Complete fault isolation (pharmacy issues never block doctors), zero data loss from double writes, high-speed local writes, and pattern standardization.
- **Negatives:** Eventual consistency (minor processing delay), added operational complexity (relays, DLQs), and mandatory outbox table purging.

#### Final Justification
The Transactional Outbox pattern prioritizes absolute availability for the core domain (*Triage & Consultations*) by decoupling external dependencies and eliminating double-write failures during system traffic peaks.