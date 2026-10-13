# ADR-002: Asynchronous Integration via Outbox Pattern for Prescription Issuance to Third-Party Pharmacy

- **Status:** Approved
- **Date:** 2026-10-12
- **Deciders:** Team Three

## Context and Problem

Upon completing a consultation, a prescription must be sent to an external third-party pharmacy. In the legacy system or under synchronous approaches, doing this directly during peak demand periods (up to 500 triage requests/s and 20,000 waiting patients) introduces two major architectural risks:
1. **Availability Coupling:** If the external pharmacy service experiences latency, rate limiting, or total downtime, medical consultation workflows within MedConnect become severely blocked.
2. **Consistency Failure (*Dual-Write Problem*):** Persisting the prescription in the local database while attempting to deliver it over the network to an external service risks dropping data. If the network call fails after the DB transaction commits, or vice versa, prescriptions are lost or duplicated.

## Considered Alternatives

- **Alternative 1: Synchronous REST Integration (Direct HTTP Call)**
  Sends an HTTP request directly to the external pharmacy API upon signing the prescription. Discarded due to tight availability coupling and latency amplification during peak traffic spikes.
- **Alternative 2: Direct Publication to Message Broker**
  Saves the prescription to the DB and immediately publishes an event to the message broker within the API thread. Discarded because network drops between the API and the broker create a dual-write inconsistency where prescriptions exist in DB but are never emitted.
- **Alternative 3: Asynchronous Integration via Transactional Outbox Pattern**
  Persists both the prescription domain entity and an outbox event record within the same local database transaction. A background worker process asynchronously reads the outbox table and publishes events to the broker and mock external pharmacy service.

## Decision

Adopt **Alternative 3 (Asynchronous Integration via Transactional Outbox Pattern)** to handle all pharmacy integrations resilience-first:

- **Event-Driven Architecture:** Upon consultation closure, emit a `PrescriptionIssued` event, completely decoupling the Core Domain from the external pharmacy's operational state.
- **Transactional Outbox Pattern:** Execute an atomic local DB transaction that writes the prescription and inserts an event record into an `Outbox` table simultaneously, guaranteeing *at-least-once* delivery.
- **Outbox Relay Worker:** Implement a lightweight background worker process that polls or listens to the Outbox table, relays events to the broker, and marks them as processed upon acknowledgment.
- **Resilience & Idempotency:** Configure exponential backoff retries and a Dead-Letter Queue (DLQ) for unhandled third-party failures. Enforce idempotency using a unique `prescriptionId`.

## Trade-offs

- **Positives (What we gain):**
  - **Fault Isolation & High Availability:** Complete operational isolation of the *Core Domain*. External pharmacy outages never block doctors or disrupt patient triage/consultations.
  - **Zero Data Loss:** Guarantees atomic persistence and eliminates dual-write risks.
  - **High Throughput Capability:** Handles 500 req/s seamlessly by converting synchronous network waits into fast, local DB writes.
- **Negatives (Risks / What we lose):**
  - **Eventually Consistent Delivery:** Introduces a minimal processing delay before the third-party pharmacy receives the prescription.
  - **Operational Complexity:** Requires maintaining an Outbox table, background relay processes, and DLQ monitoring infrastructure.

## Final Justification

Implementing the Transactional Outbox Pattern is the optimal decision because it prioritizes the absolute availability of MedConnect's core clinical domain (*Intelligent Triage and Consultations*) by fully decoupling secondary supporting subdomains. It eliminates dual-write failures and guarantees reliable, resilient delivery even when external third-party systems fail under peak system loads.