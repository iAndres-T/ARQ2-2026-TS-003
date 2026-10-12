# ADR 002: Asynchronous Integration via Outbox Pattern for Prescription Issuance to Third-Party Pharmacy

## Context and Problem Statement
Upon completing a consultation, a prescription must be sent to an external pharmacy. Doing this synchronously during peak demand periods (up to 500 req/s) introduces two major risks:
1. **Availability Coupling**: If the external pharmacy goes down or experiences latency, medical care within MedConnect becomes blocked.
2. **Consistency Failure (*Dual-Write*)**: Saving the prescription to the database while failing to deliver it over the network would result in a lost prescription.

## Considered Alternatives
- **Alternative 1 (Synchronous REST)**: Direct HTTP call upon signing. *Discarded due to tight latency and availability coupling.*
- **Alternative 2 (Direct Publication to Broker)**: Save to DB and publish event directly. *Discarded due to the risk of dropping events if network failures occur (Dual-Write).*
- **Alternative 3 (Event-Driven + Outbox Pattern + Mock)**: Persist both the prescription and the event record within the same local transaction; a background Worker asynchronously publishes `PrescriptionIssued` to the broker. *(Selected)*.

## Decision Outcome
Adopt **Alternative 3**:
1. **Event-Driven**: Emit the `PrescriptionIssued` event when the consultation ends, fully decoupled from the pharmacy's operational state.
2. **Outbox Pattern**: Perform an atomic save of the prescription and the event in MedConnect's DB to guarantee *at-least-once* delivery.
3. **Resilience & Idempotency**: Implement exponential backoff retries, a Dead-Letter Queue (DLQ) for unhandled failures, and assign a unique `prescriptionId` as defined in `spec.md`.

## Trade-offs
- **Positives**: Complete isolation of the *Core Domain*, zero downtime or blocking for doctors during third-party outages, and high throughput capacity during peak loads.
- **Negatives**: Eventual consistency (minimal processing delay) and the requirement of an additional *Worker/Relay* process to handle the Outbox table.

## Final Justification
Prioritizes the absolute availability of the core domain (*Intelligent Triage*) by decoupling secondary supporting services. Guarantees resilience and zero data loss.