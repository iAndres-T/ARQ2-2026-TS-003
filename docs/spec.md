# MedConnect – API and Contract Specification (OpenSpec)

> **Version:** 0.1.0

---

## Table of Contents

1. [Overview](#1-overview)
2. [Domain Rules](#2-domain-rules)
3. [Data Schemas](#3-data-schemas)
4. [REST API](#4-rest-api)
5. [Asynchronous Events](#5-asynchronous-events)
6. [External Integration Contracts](#6-external-integration-contracts)
7. [Requirements and Scenarios](#7-requirements-and-scenarios)
8. [Non-Functional Requirements](#8-non-functional-requirements)
9. [Open Questions](#9-open-questions)

---

## 1. Overview

### 1.1 Problem (summary)

Legacy system: single SQL Server DB, FIFO table as queue, recording workload on the same servers as scheduling.
Failures to fix:

1. No clinical prioritization (chest pain waits behind mild cases).
2. Extreme coupling (recordings saturate network/disk and take down scheduling).

Winter peak: 500 triage requests/s, 20,000 patients waiting, ~500 doctors on shift.

### 1.2 Core Domain

Triage and prioritized care queue management (`triage-service` + `care-queue-service`).

### 1.3 Services and Responsibilities

| Service | Bounded Context | Responsibility | Own DB |
|---|---|---|---|
| api-gateway | – | JWT validation, rate limiting, routing | – |
| identity-service | Identity & Access | Registration, login, tokens, roles `PATIENT` / `MEDICAL_STAFF` | `identity_db` |
| consultation-service | Consultation | `Consultation` aggregate, state machine, video session link | `consultation_db` |
| triage-service | Triage (Core) | Rule-based priority (HIGH/MEDIUM/LOW), re-triage, override | `triage_db` |
| care-queue-service | Care Queue (Core) | 3 priority queues, FIFO, atomic claim, aging | Redis + `queue_db` |
| scheduling-service | Scheduling | Doctor shifts and availability (FREE/BUSY/OFFLINE) | `scheduling_db` |
| prescription-service | Prescription | Register prescription, emit `PrescriptionIssued` (outbox) | `prescription_db` |
| recording-service | Recording | Isolated; recordings to object storage, metadata only | `recording_db` + S3/MinIO |
| audit-service | Audit | Append-only event log | `audit_db` |
| mock-pharmacy-consumer | External (mock) | Receives and logs `PrescriptionIssued` | – |

### 1.4 Architecture Diagram

```
TODO: final diagram (draft below)

Client ─► API Gateway ─► identity | consultation | triage | care-queue | scheduling | prescription | recording | audit
                              │
                              └──► Message Broker (topic exchange, queues, DLQs)
                                        ├──► mock-pharmacy-consumer
                                        └──► recording-service ─► Object Storage
```

### 1.5 Architectural Rules

- Database per service. No cross-service table access.
- Async by default (events). Sync only for client APIs and external ACL calls.
- Transactional outbox on producers. Idempotent consumers.
- Recording runs isolated (bulkhead): its failure must never affect triage, queues, consultations or scheduling.

### 1.6 API Conventions

| Item | Convention |
|---|---|
| Base path | `/api/v1` |
| Auth | `Authorization: Bearer <JWT>` (claims: `sub`, `role`, `doctorId`, `scopes`) |
| Errors | RFC 7807 (`application/problem+json`) |
| IDs / time | UUID v4 / ISO-8601 UTC |
| Idempotency | `Idempotency-Key` header on marked POSTs |
| Tracing | `X-Correlation-Id` |
| Pagination | `limit` + `cursor` (TODO: confirm) |

Common statuses: `401`, `403`, `429`, `500`, `503`.

---

## 2. Domain Rules

### 2.1 Consultation States

`REQUESTED → TRIAGED → WAITING → ASSIGNED → IN_PROGRESS → COMPLETED`
Exits: `CANCELLED` (patient leaves), `NO_SHOW` (patient does not connect once assigned).

### 2.2 Queue Invariants

1. A patient is in at most one queue at a time.
2. FIFO within a priority (key: original `requestedAt`).
3. Claim order: HIGH → MEDIUM → LOW.
4. Claim is atomic (one patient, one doctor).
5. Stale events discarded by `triageVersion`.
6. Aging: LOW → MEDIUM after threshold. TBD: threshold value.

### 2.3 Triage Rules (rule set `v1`)

Rules are evaluated **in order**. The first match wins. Triage is purely rule-based (no AI/ML). The free-text `description` is stored but **never** used for classification.

| # | Condition | Priority | `ruleId` |
|---|---|---|---|
| 1 | `redFlags.difficultyBreathing = true` **or** `redFlags.lossOfConsciousness = true` | HIGH | `R1_RED_FLAG` |
| 2 | `mainSymptom ∈ {CHEST_PAIN, SHORTNESS_OF_BREATH}` | HIGH | `R2_CRITICAL_SYMPTOM` |
| 3 | `mainSymptom = FEVER` **and** `painLevel ≥ 7` | MEDIUM | `R3_FEVER_HIGH_PAIN` |
| 4 | `mainSymptom = FEVER` **and** (`age ≤ 2` **or** `age ≥ 75`) | MEDIUM | `R4_FEVER_VULNERABLE` |
| 5 | `painLevel ≥ 8` (any symptom) | MEDIUM | `R5_SEVERE_PAIN` |
| 6 | Anything else | LOW | `R6_DEFAULT` |

- The rule set is versioned (`ruleSetVersion`). Each assessment records the `ruleId` and version that produced it.
- Medical staff can **override** the result. The override creates a `PriorityOverridden` event, is audited, and always wins over automatic re-triage until symptoms are updated again.
---

## 3. Data Schemas


| Schema | Used by |
|---|---|
| `SymptomReport` | consultation, triage |
| `Consultation` | consultation |
| `TriageAssessment` | triage |
| `QueueEntry` | care-queue |
| `Assignment` | care-queue |
| `DoctorAvailability` / `Shift` | scheduling |
| `Prescription` / `PrescriptionItem` | prescription |
| `RecordingMetadata` | recording |
| `AuditEvent` | audit |
| `Problem` (RFC 7807) | all |

```yaml
SymptomReport:
  type: object
  required: [mainSymptom, painLevel, symptomDuration, age, redFlags]
  properties:
    mainSymptom:     { $ref: '#/components/schemas/MainSymptom' }
    painLevel:       { type: integer, minimum: 1, maximum: 10 }
    symptomDuration:
      type: object
      required: [value, unit]
      properties:
        value: { type: integer, minimum: 1, maximum: 999 }
        unit:  { type: string, enum: [MINUTES, HOURS, DAYS] }
    age:             { type: integer, minimum: 0, maximum: 120 }
    redFlags:
      type: object
      required: [difficultyBreathing, lossOfConsciousness]
      properties:
        difficultyBreathing: { type: boolean }
        lossOfConsciousness: { type: boolean }
    description:     { type: string, maxLength: 1000, nullable: true }
```

---

## 4. REST API

Endpoint summary (details per endpoint to be completed using the template in 4.10).

| Service | Method | Path | Auth | Success | Errors |
|---|---|---|---|---|---|
| identity | POST | `/auth/register` | public | 201 | 400, 409, 422 |
| identity | POST | `/auth/login` | public | 200 | 400, 401 |
| identity | POST | `/auth/refresh` | refresh token | 200 | 401 |
| consultation | POST | `/consultations` | PATIENT | 202 | 400, 409, 422 |
| consultation | GET | `/consultations/{id}` | owner / assigned doctor | 200 | 403, 404 |
| consultation | PUT | `/consultations/{id}/symptoms` | PATIENT (owner) | 202 | 404, 409, 422 |
| consultation | POST | `/consultations/{id}/cancel` | PATIENT (owner) | 200 | 404, 409 |
| consultation | POST | `/consultations/{id}/join` | PATIENT (owner) | 200 | 409, 502 |
| consultation | POST | `/consultations/{id}/start` | MEDICAL_STAFF | 200 | 403, 409 |
| consultation | POST | `/consultations/{id}/complete` | MEDICAL_STAFF | 200 | 403, 409 |
| triage | POST | `/triage/consultations/{id}/override` | MEDICAL_STAFF | 200 | 403, 404, 409, 422 |
| triage | GET | `/triage/consultations/{id}` | owner / staff | 200 | 403, 404 |
| care-queue | POST | `/queue/claims` | MEDICAL_STAFF (doctor) | 201 / 204 | 403, 409 |
| care-queue | GET | `/queue/stats` | MEDICAL_STAFF | 200 | 403 |
| scheduling | PUT | `/doctors/me/availability` | MEDICAL_STAFF (doctor) | 200 | 409, 422 |
| scheduling | GET | `/doctors/availability` | MEDICAL_STAFF | 200 | 403 |
| scheduling | POST / GET | `/shifts` | MEDICAL_STAFF (`scheduling:manage`) | 201 / 200 | 409, 422 |
| prescription | POST | `/prescriptions` | MEDICAL_STAFF (assigned) | 202 | 403, 409, 422 |
| prescription | GET | `/prescriptions/{id}` | issuer / owner | 200 | 403, 404 |
| recording | GET | `/consultations/{id}/recording` | MEDICAL_STAFF (`recordings:read`) | 200 | 403, 404 |
| recording | POST | `/webhooks/video-provider` | provider signature | 202 | 401, 409 |
| audit | GET | `/audit/events` | MEDICAL_STAFF (`audit:read`) | 200 | 403 |

### 4.1 Drafted Endpoint: `POST /consultations`

- **Auth:** Bearer JWT, role `PATIENT` (identity from `sub`, never from body)
- **Headers:** `Idempotency-Key` (required)
- **Request body:** `SymptomReport`
- **Behavior:** persist in `REQUESTED`, write `ConsultationRequested` to outbox, return immediately (triage is async).

| Status | Meaning |
|---|---|
| 202 | Accepted for triage |
| 400 | Malformed body / missing `Idempotency-Key` |
| 401 / 403 | Not authenticated / not a PATIENT |
| 409 | Patient already has an active consultation |
| 422 | Validation error |
| 429 / 503 | Rate limit / unavailable |

```json
// Request
{ "mainSymptom": "CHEST_PAIN", "painLevel": 9, "symptomDuration": { "value": 30, "unit": "MINUTES" },
  "age": 58, "redFlags": { "difficultyBreathing": false, "lossOfConsciousness": false }, "description": null }

// 202 Response
{ "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "status": "REQUESTED", "requestedAt": "2026-12-15T10:15:30Z" }
```

### 4.2 Drafted Endpoint: `POST /queue/claims`

- **Auth:** Bearer JWT, role `MEDICAL_STAFF` with `doctorId`
- **Headers:** `Idempotency-Key` (required)
- **Behavior:** atomic pull of next patient: HIGH → MEDIUM → LOW, FIFO inside each queue. Doctor must be `FREE`.

| Status | Meaning |
|---|---|
| 201 | Patient assigned (`Assignment`) |
| 204 | No patients waiting |
| 400 | Missing `Idempotency-Key` |
| 401 / 403 | Not authenticated / not a doctor |
| 409 | Doctor not `FREE` |
| 503 | Queue store unavailable |

```json
// 201 Response
{ "consultationId": "7b1f2c4e-...", "patientId": "3f8a1d52-...", "doctorId": "9c2e7a10-...",
  "priority": "HIGH", "assignedAt": "2026-12-15T10:21:05Z" }
```

### 4.10 Template for Remaining Endpoints

```
#### <METHOD> <path>
- Auth:
- Headers:
- Request body:
- Responses: (2xx / 4xx / 5xx table)
- Example:
```

---

## 5. Asynchronous Events

### 5.1 Messaging Conventions (draft)

| Item | Definition |
|---|---|
| Broker | RabbitMQ (TBD: confirm vs Kafka / Redis Streams) |
| Exchange | `medconnect.events` (topic); `medconnect.pharmacy` for the pharmacy integration |
| Routing key | `<aggregate>.<event>` (e.g., `consultation.requested`) |
| Delivery | At-least-once, transactional outbox |
| Ordering | Per aggregate (`aggregateId`) |
| Idempotency | Consumers dedupe by `eventId` |
| Retries | Exponential backoff, max 5 attempts (TBD: intervals) |
| DLQ | `<queue>.dlq` per consumer queue |

Envelope fields: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer`, `aggregateId`, `aggregateVersion`, `correlationId`, `payload`.

### 5.2 Event Catalog



### 5.3 Drafted Event:


### Template for Remaining Events

```
#### <EventName>
- Producer:
- Consumers:
- Queue / routing key:
- Payload schema:
- Delivery guarantees: ordering / idempotency / retries
```

---

## 6. External Integration Contracts


---

## 7. Requirements and Scenarios

### Feature 1 – Authentication with Strict Roles
- **R-F1:** The system SHALL issue JWTs with exactly one role (`PATIENT` or `MEDICAL_STAFF`) and every service SHALL enforce role and ownership.
- **S-F1-01:** **WHEN** a patient calls a staff-only endpoint (e.g., `POST /queue/claims`) **THEN** the system returns `403`.
- **S-F1-02:** **WHEN** a request has no valid token **THEN** the system returns `401`.

### Feature 2 – Independent Microservice for Querying Recordings
- **R-F2:** Consultation recordings SHALL be served by an independent service (`recording-service`) with its own deployment, resources and database. Files live in object storage; only metadata is stored in the database.
- **S-F2-01:** **WHEN** an authorized doctor requests `GET /consultations/{id}/recording` **THEN** the system returns `200` with metadata (ID, duration, date) and a short-lived pre-signed URL.
- **S-F2-02:** **WHEN** a consultation is completed **THEN** `ConsultationCompleted` is emitted and the recording is processed asynchronously and stored in object storage, without involving scheduling or triage.
- **S-F2-03:** **WHEN** recording-service fails or saturates network or disk **THEN** triage, queues and scheduling keep working and `RecordingFailed` is emitted after retries.

### Feature 3 – Automatic Triage Engine with Prioritized Async Queues
- **R-F3:** The system SHALL classify each request as HIGH/MEDIUM/LOW and enqueue it asynchronously; a patient SHALL be in only one queue.
- **S-F3-01:** **WHEN** a patient submits chest pain **THEN** the system returns `202`, triage assigns HIGH and the patient enters the HIGH queue.
- **S-F3-02:** **WHEN** medical staff overrides a priority **THEN** `PriorityOverridden` is emitted, the patient moves to the new queue and the action is audited.

### Feature 4 – Doctor Consumption Respecting Criticality
- **R-F4:** An available doctor SHALL pull patients HIGH → MEDIUM → LOW, FIFO within each level.
- **S-F4-01:** **WHEN** a free doctor claims and the queues hold 200 LOW and 1 HIGH patient **THEN** the HIGH patient is assigned first.
- **S-F4-02:** **WHEN** two doctors claim at the same time for one waiting patient **THEN** only one receives the patient.

### Feature 5 – Asynchronous Prescription Registration
- **R-F5:** Prescription registration SHALL NOT depend synchronously on the pharmacy.
- **S-F5-01:** **WHEN** the assigned doctor registers a prescription **THEN** the system returns `202` and emits `PrescriptionIssued`.
- **S-F5-02:** **WHEN** the pharmacy is down **THEN** delivery is retried and, after the retry limit, the message goes to the DLQ without affecting the consultation.
