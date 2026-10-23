# MedConnect – API and Contract Specification (OpenSpec)

In the legacy architecture, MedConnect relied on a single centralized SQL Server database to store medical records, video call resource management, and doctor scheduling, while queueing patients in a traditional FIFO table and downloading, processing, and storing external video call recordings on those same servers. During the winter peak—when demand grows tenfold to up to 20,000 waiting patients and 500 triage requests per second with only about 500 doctors on shift—this extreme coupling caused the database to collapse, placed critical patients (such as a patient reporting severe chest pain from a heart attack) in a FIFO queue behind 200 people with a mild cold for hours, and allowed video recording network or disk saturation to crash the entire scheduling module, leaving doctors unable to work. This specification defines the target decoupled microservices contracts and event-driven architecture (aligned with **ADR-001** through **ADR-005**) that resolve these failure modes across the five mandatory features.

## 1. Data Schemas of the Main Domains

OpenAPI 3.0 component schemas shared by the APIs and the event payloads. These models replace the centralized SQL Server tables by separating the consultation lifecycle and structured symptom reporting (`SymptomReport`) for deterministic triage (**ADR-001**), prioritized queue entries (`HIGH`, `MEDIUM`, `LOW`) that preserve original arrival time (`requestedAt`) for fair FIFO ordering and aging (**ADR-004**), doctor shift and availability management (`Shift`, `DoctorAvailability`), outbox-backed prescriptions (`Prescription`, **ADR-002**), and lightweight recording metadata (`RecordingMetadata`) decoupled from raw video files (**ADR-003**).

```yaml
openapi: 3.0.3
info:
  title: MedConnect API
  version: 1.0.0
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
  schemas:
    Priority:
      type: string
      enum: [HIGH, MEDIUM, LOW]

    ConsultationStatus:
      type: string
      enum: [REQUESTED, TRIAGED, WAITING, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW]
      description: >
        Allowed transitions: REQUESTED -> TRIAGED -> WAITING -> ASSIGNED -> IN_PROGRESS -> COMPLETED.
        CANCELLED is reachable from REQUESTED, TRIAGED, WAITING and ASSIGNED (patient leaves).
        NO_SHOW is reachable from ASSIGNED (patient does not connect).

    MainSymptom:
      type: string
      enum: [CHEST_PAIN, SHORTNESS_OF_BREATH, FEVER, COUGH, HEADACHE,
             ABDOMINAL_PAIN, SORE_THROAT, NAUSEA_VOMITING, DIZZINESS, OTHER]

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
        age: { type: integer, minimum: 0, maximum: 120 }
        redFlags:
          type: object
          required: [difficultyBreathing, lossOfConsciousness]
          properties:
            difficultyBreathing: { type: boolean }
            lossOfConsciousness: { type: boolean }
        description: { type: string, maxLength: 1000, nullable: true }

    Consultation:
      type: object
      required: [consultationId, patientId, status, symptoms, requestedAt, version]
      properties:
        consultationId:  { type: string, format: uuid }
        patientId:       { type: string, format: uuid }
        status:          { $ref: '#/components/schemas/ConsultationStatus' }
        currentPriority: { allOf: [{ $ref: '#/components/schemas/Priority' }], nullable: true }
        symptoms:        { $ref: '#/components/schemas/SymptomReport' }
        doctorId:        { type: string, format: uuid, nullable: true }
        videoSession:
          type: object
          nullable: true
          properties:
            sessionId: { type: string }
            joinUrl:   { type: string, format: uri }
        requestedAt:     { type: string, format: date-time }
        assignedAt:      { type: string, format: date-time, nullable: true }
        startedAt:       { type: string, format: date-time, nullable: true }
        completedAt:     { type: string, format: date-time, nullable: true }
        version:         { type: integer, description: Monotonic aggregate version }

    TriageAssessment:
      type: object
      properties:
        consultationId:  { type: string, format: uuid }
        priority:        { $ref: '#/components/schemas/Priority' }
        source:          { type: string, enum: [AUTOMATIC, OVERRIDE] }
        ruleId:          { type: string, nullable: true, example: R2_CRITICAL_SYMPTOM }
        ruleSetVersion:  { type: string, example: v1 }
        triageVersion:   { type: integer, description: Strictly increasing per consultation }
        overriddenBy:    { type: string, format: uuid, nullable: true }
        reason:          { type: string, nullable: true }
        assessedAt:      { type: string, format: date-time }

    QueueEntry:
      type: object
      properties:
        consultationId:  { type: string, format: uuid }
        queue:           { $ref: '#/components/schemas/Priority' }
        triageVersion:   { type: integer }
        requestedAt:     { type: string, format: date-time, description: FIFO key (original request time) }
        enqueuedAt:      { type: string, format: date-time }

    Assignment:
      type: object
      properties:
        consultationId:  { type: string, format: uuid }
        patientId:       { type: string, format: uuid }
        doctorId:        { type: string, format: uuid }
        priority:        { $ref: '#/components/schemas/Priority' }
        symptoms:        { $ref: '#/components/schemas/SymptomReport' }
        assignedAt:      { type: string, format: date-time }

    DoctorAvailability:
      type: object
      properties:
        doctorId:  { type: string, format: uuid }
        status:    { type: string, enum: [FREE, BUSY, OFFLINE] }
        since:     { type: string, format: date-time }
        shiftId:   { type: string, format: uuid, nullable: true }

    Shift:
      type: object
      required: [doctorId, startsAt, endsAt]
      properties:
        shiftId:   { type: string, format: uuid, readOnly: true }
        doctorId:  { type: string, format: uuid }
        startsAt:  { type: string, format: date-time }
        endsAt:    { type: string, format: date-time }

    PrescriptionItem:
      type: object
      required: [medicationName, dosage, frequency, durationDays]
      properties:
        medicationName: { type: string, maxLength: 200 }
        dosage:         { type: string, example: 100 mg }
        frequency:      { type: string, example: once daily }
        durationDays:   { type: integer, minimum: 1, maximum: 365 }
        instructions:   { type: string, maxLength: 500, nullable: true }

    Prescription:
      type: object
      properties:
        prescriptionId: { type: string, format: uuid }
        consultationId: { type: string, format: uuid }
        patientId:      { type: string, format: uuid }
        doctorId:       { type: string, format: uuid }
        items:          { type: array, minItems: 1, maxItems: 20, items: { $ref: '#/components/schemas/PrescriptionItem' } }
        notes:          { type: string, maxLength: 1000, nullable: true }
        deliveryStatus: { type: string, enum: [PENDING, PUBLISHED, DEAD_LETTERED] }
        issuedAt:       { type: string, format: date-time }

    RecordingMetadata:
      type: object
      description: Only metadata is stored in the database; the video file lives in object storage.
      properties:
        recordingId:     { type: string, format: uuid }
        consultationId:  { type: string, format: uuid }
        status:          { type: string, enum: [PENDING, PROCESSING, AVAILABLE, FAILED] }
        url:             { type: string, nullable: true, description: Internal object storage URI }
        durationSeconds: { type: integer, nullable: true }
        recordedAt:      { type: string, format: date-time, nullable: true }
        sizeBytes:       { type: integer, nullable: true }
        checksumSha256:  { type: string, nullable: true }
        downloadUrl:     { type: string, format: uri, nullable: true, description: Pre-signed URL, TTL 300 s, only when AVAILABLE }

    AuditEvent:
      type: object
      properties:
        eventId:      { type: string, format: uuid }
        eventType:    { type: string }
        aggregateId:  { type: string, format: uuid }
        actorId:      { type: string, format: uuid, nullable: true }
        occurredAt:   { type: string, format: date-time }
        payload:      { type: object, additionalProperties: true }

    Problem:
      type: object
      required: [type, title, status]
      properties:
        type:          { type: string, format: uri }
        title:         { type: string }
        status:        { type: integer }
        detail:        { type: string }
        instance:      { type: string }
        correlationId: { type: string, format: uuid }
        errors:
          type: array
          items:
            type: object
            properties:
              field:   { type: string }
              message: { type: string }
```

## 2. APIs

Conventions for all endpoints:

- Base path: `/api/v1`, exposed through the API Gateway.
- Authentication: `Authorization: Bearer <JWT>` (RS256, validated locally by the API Gateway and every service against the identity-service JWKS without synchronous calls to `identity-service`, per **ADR-005**). Claims: `sub`, `role` (`PATIENT` or `MEDICAL_STAFF`), `doctorId` (doctors only), `scopes` (`scheduling:manage`, `recordings:read`, `audit:read`). The API Gateway enforces coarse-grained route access by role, while each service enforces fine-grained ownership and assigned doctor–patient relationship rules using local event-driven projections. For patients, `sub` is the `patientId`. The patient identity always comes from the token, never from the request body.
- Errors: RFC 7807 `application/problem+json` using the `Problem` schema.
- IDs are UUID v4. Timestamps are ISO-8601 UTC.
- `Idempotency-Key` header is **required** on the POST endpoints that state it. The original response is replayed for the same key for 24 h.
- `X-Correlation-Id` is accepted or generated by the gateway and propagated into events.
- `429` (rate limit, with `Retry-After`) can be returned by any endpoint.

**POST /auth/register**
- **Authentication:** none (public). Creates `PATIENT` accounts only. Medical staff accounts are provisioned by an internal administrative process.
- **Request body:**
```json
{ "email": "ana.torres@example.com", "password": "S3cure!Passw0rd", "fullName": "Ana Torres", "birthDate": "1968-04-12" }
```
- **Responses:**

| Status | Meaning |
|---|---|
| 201 | Account created |
| 400 | Malformed JSON |
| 409 | Email already registered |
| 422 | Validation error (weak password, invalid email) |
| 500 | Unexpected error |

- **Example (201):**
```json
{ "patientId": "3f8a1d52-6b7c-4c0e-9a41-2e5d8c7b9f10", "email": "ana.torres@example.com", "role": "PATIENT" }
```

**POST /auth/login**
- **Authentication:** none (public)
- **Request body:**
```json
{ "email": "ana.torres@example.com", "password": "S3cure!Passw0rd" }
```
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | Authenticated |
| 400 | Malformed JSON |
| 401 | Invalid credentials |
| 423 | Account temporarily locked (too many attempts) |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "accessToken": "eyJhbGciOiJSUzI1NiIs...", "refreshToken": "rt_9f1c...", "tokenType": "Bearer", "expiresIn": 3600, "role": "PATIENT" }
```

**POST /auth/refresh**
- **Authentication:** none (refresh token in the body)
- **Request body:**
```json
{ "refreshToken": "rt_9f1c..." }
```
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | New token pair (same shape as login) |
| 400 | Malformed JSON |
| 401 | Refresh token invalid, expired or revoked |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "accessToken": "eyJhbGciOiJSUzI1NiIs...", "refreshToken": "rt_a77d...", "tokenType": "Bearer", "expiresIn": 3600, "role": "PATIENT" }
```

**GET /.well-known/jwks.json**
- **Authentication:** none (public keys used by every service to validate JWT signatures)
- **Request body:** none
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | JWKS document |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "keys": [ { "kty": "RSA", "kid": "2026-10", "use": "sig", "alg": "RS256", "n": "0vx7agoebGcQ...", "e": "AQAB" } ] }
```

**POST /consultations**
- **Authentication:** Bearer JWT, role `PATIENT`. Header `Idempotency-Key` required.
- **Request body:** `SymptomReport`
```json
{
  "mainSymptom": "CHEST_PAIN",
  "painLevel": 9,
  "symptomDuration": { "value": 30, "unit": "MINUTES" },
  "age": 58,
  "redFlags": { "difficultyBreathing": false, "lossOfConsciousness": false },
  "description": "Pressure in the chest radiating to the left arm"
}
```
- **Behavior:** validates, persists the consultation as `REQUESTED`, writes `ConsultationRequested` to the transactional outbox (**ADR-002**) and returns `202` immediately so triage runs asynchronously. This replaces the legacy synchronous SQL table insertion that collapsed under winter peaks of 500 triage requests per second.
- **Responses:**

| Status | Meaning |
|---|---|
| 202 | Accepted for triage |
| 400 | Malformed JSON or missing `Idempotency-Key` |
| 401 | Missing or invalid token |
| 403 | Role is not `PATIENT` |
| 409 | Patient already has an active consultation (`REQUESTED` to `IN_PROGRESS`) |
| 422 | Semantic validation error (e.g. `painLevel` out of range) |
| 429 | Rate limit exceeded |
| 503 | Service temporarily unavailable |

- **Example (202):**
```json
{ "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "status": "REQUESTED", "requestedAt": "2026-12-15T10:15:30Z" }
```

**GET /consultations/{consultationId}**
- **Authentication:** Bearer JWT. `PATIENT` only for their own consultation. `MEDICAL_STAFF` only for consultations assigned to their `doctorId`.
- **Request body:** none
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | `Consultation` |
| 401 / 403 | Not authenticated / not the owner or assigned doctor |
| 404 | Consultation not found |
| 500 | Unexpected error |

- **Example (200):**
```json
{
  "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
  "patientId": "3f8a1d52-6b7c-4c0e-9a41-2e5d8c7b9f10",
  "status": "WAITING",
  "currentPriority": "HIGH",
  "symptoms": { "mainSymptom": "CHEST_PAIN", "painLevel": 9, "symptomDuration": { "value": 30, "unit": "MINUTES" }, "age": 58, "redFlags": { "difficultyBreathing": false, "lossOfConsciousness": false }, "description": null },
  "doctorId": null,
  "videoSession": null,
  "requestedAt": "2026-12-15T10:15:30Z",
  "assignedAt": null,
  "startedAt": null,
  "completedAt": null,
  "version": 3
}
```

**PUT /consultations/{consultationId}/symptoms**
- **Authentication:** Bearer JWT, role `PATIENT` (owner)
- **Request body:** `SymptomReport` (full replacement)
```json
{
  "mainSymptom": "SHORTNESS_OF_BREATH",
  "painLevel": 6,
  "symptomDuration": { "value": 45, "unit": "MINUTES" },
  "age": 58,
  "redFlags": { "difficultyBreathing": true, "lossOfConsciousness": false },
  "description": "Now it is hard to breathe"
}
```
- **Behavior:** emits `SymptomsUpdated`, which triggers re-triage and, if the priority changes, a move to another queue.
- **Responses:**

| Status | Meaning |
|---|---|
| 202 | Update accepted |
| 400 / 422 | Malformed or invalid body |
| 401 / 403 | Not authenticated / not the owner |
| 404 | Consultation not found |
| 409 | Status is not `TRIAGED` or `WAITING` |
| 503 | Service temporarily unavailable |

- **Example (202):**
```json
{ "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "status": "WAITING" }
```

**POST /consultations/{consultationId}/cancel**
- **Authentication:** Bearer JWT, role `PATIENT` (owner)
- **Request body (optional):**
```json
{ "reason": "Feeling better" }
```
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | Consultation cancelled |
| 401 / 403 | Not authenticated / not the owner |
| 404 | Consultation not found |
| 409 | Status is `IN_PROGRESS`, `COMPLETED`, `CANCELLED` or `NO_SHOW` |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "status": "CANCELLED" }
```

**POST /consultations/{consultationId}/join**
- **Authentication:** Bearer JWT, role `PATIENT` (owner)
- **Request body:** none
- **Behavior:** the patient connects after being assigned. Returns the video join link and marks the patient as present (prevents `NO_SHOW`).
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | Join link returned |
| 401 / 403 | Not authenticated / not the owner |
| 404 | Consultation not found |
| 409 | Status is not `ASSIGNED`, or already `NO_SHOW` |
| 502 | Video provider error |

- **Example (200):**
```json
{ "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "sessionId": "abc123", "joinUrl": "https://video.provider.example/room/abc123?t=pt_tok" }
```

**POST /consultations/{consultationId}/start**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF` (the assigned doctor)
- **Request body:** none
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | Consultation `IN_PROGRESS` (includes the doctor's join URL) |
| 401 / 403 | Not authenticated / not the assigned doctor |
| 404 | Consultation not found |
| 409 | Status is not `ASSIGNED`, or the patient has not joined |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "status": "IN_PROGRESS", "joinUrl": "https://video.provider.example/room/abc123?t=dr_tok" }
```

**POST /consultations/{consultationId}/complete**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF` (the assigned doctor)
- **Request body:**
```json
{ "clinicalSummary": "Suspected angina. Referred to cardiology for ECG and troponin tests.", "diagnosisCodes": ["I20.9"] }
```
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | Consultation `COMPLETED` |
| 400 / 422 | Malformed or invalid body |
| 401 / 403 | Not authenticated / not the assigned doctor |
| 404 | Consultation not found |
| 409 | Status is not `IN_PROGRESS` |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "status": "COMPLETED", "completedAt": "2026-12-15T10:52:10Z" }
```

**POST /triage/consultations/{consultationId}/override**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF`
- **Request body:**
```json
{ "newPriority": "HIGH", "reason": "Patient reports radiating pain on call-back" }
```
- **Behavior:** emits `PriorityOverridden` (audited). The patient moves atomically to the new queue.
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | Override applied |
| 400 / 422 | Invalid body (`reason` is mandatory, 5 to 500 chars) |
| 401 / 403 | Not authenticated / role is not `MEDICAL_STAFF` |
| 404 | Consultation not found |
| 409 | Consultation is not `TRIAGED` or `WAITING`, or `newPriority` equals the current priority |
| 503 | Service temporarily unavailable |

- **Example (200):**
```json
{
  "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
  "previousPriority": "MEDIUM",
  "newPriority": "HIGH",
  "source": "OVERRIDE",
  "triageVersion": 3,
  "overriddenBy": "5d3c8b21-0e4f-4a77-b6d9-1c2a9e8f7b65",
  "assessedAt": "2026-12-15T10:20:00Z"
}
```

**GET /triage/consultations/{consultationId}**
- **Authentication:** Bearer JWT. `MEDICAL_STAFF` for any consultation. `PATIENT` only for their own (`ruleId`, `reason` and `overriddenBy` are omitted for patients).
- **Request body:** none
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | `TriageAssessment` |
| 401 / 403 | Not authenticated / not allowed |
| 404 | Consultation not found or not triaged yet |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "priority": "HIGH", "source": "AUTOMATIC", "ruleId": "R2_CRITICAL_SYMPTOM", "ruleSetVersion": "v1", "triageVersion": 1, "overriddenBy": null, "reason": null, "assessedAt": "2026-12-15T10:15:31Z" }
```

**POST /queue/claims**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF` with a `doctorId` claim. Header `Idempotency-Key` required (a retry with the same key returns the same assignment).
- **Request body:** none. Optional query `waitSeconds` (0 to 30) for long-polling when all queues are empty.
- **Behavior:** replaces the legacy single FIFO SQL table—where a patient with severe chest pain waited hours behind 200 mild cold cases—with a prioritized pull mechanism (**ADR-001**, **ADR-004**). An available (`FREE`) doctor among the ~500 on shift **pulls** the next patient. A single atomic Redis Lua script pops the oldest entry from HIGH, then MEDIUM, then LOW (strict priority between levels, FIFO by original `requestedAt` inside each level), ensuring a critical patient always bypasses mild cases and can be claimed by only one doctor. The patient moves to a claimed set with a **lease** (default 30 s), which is confirmed once `PatientAssigned` is committed to the outbox. A recovery job returns expired leases to their original queue, keeping the original `requestedAt`.
- **Responses:**

| Status | Meaning |
|---|---|
| 201 | Patient claimed and assigned (`Assignment`) |
| 204 | No patients waiting |
| 400 | Missing `Idempotency-Key` |
| 401 / 403 | Not authenticated / not a doctor |
| 409 | Doctor is not `FREE` (busy, offline or no active shift) |
| 503 | Queue store unavailable |

- **Example (201):**
```json
{
  "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
  "patientId": "3f8a1d52-6b7c-4c0e-9a41-2e5d8c7b9f10",
  "doctorId": "9c2e7a10-5b8d-4f3a-a1c6-7e0d2b4f9a58",
  "priority": "HIGH",
  "symptoms": { "mainSymptom": "CHEST_PAIN", "painLevel": 9, "symptomDuration": { "value": 30, "unit": "MINUTES" }, "age": 58, "redFlags": { "difficultyBreathing": false, "lossOfConsciousness": false }, "description": null },
  "assignedAt": "2026-12-15T10:21:05Z"
}
```

**GET /queue/stats**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF`
- **Request body:** none
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | Queue statistics |
| 401 / 403 | Not authenticated / not `MEDICAL_STAFF` |
| 500 | Unexpected error |

- **Example (200):**
```json
{
  "generatedAt": "2026-12-15T10:25:00Z",
  "queues": {
    "HIGH":   { "waiting": 12,   "oldestWaitSeconds": 95 },
    "MEDIUM": { "waiting": 640,  "oldestWaitSeconds": 1480 },
    "LOW":    { "waiting": 9300, "oldestWaitSeconds": 5320 }
  },
  "freeDoctors": 4
}
```

**PUT /doctors/me/availability**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF` with `doctorId`
- **Request body:**
```json
{ "status": "FREE" }
```
- **Behavior:** the scheduling module manages doctors' shifts and real-time availability (`FREE`, `BUSY`, `OFFLINE`) to coordinate who can pull patients from the priority queues, running completely isolated from video recording workloads so scheduling never crashes when recordings are processed. The doctor sets themselves `FREE` or `OFFLINE`. `BUSY` is system-managed (set on `PatientAssigned`). After `ConsultationCompleted` or `ConsultationNoShow` the doctor returns to `FREE` automatically if the shift is active and the doctor did not go `OFFLINE`.
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | Availability updated |
| 400 / 422 | Invalid status (only `FREE` or `OFFLINE` accepted) |
| 401 / 403 | Not authenticated / not a doctor |
| 409 | Doctor is `BUSY`, or there is no active shift (for `FREE`) |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "doctorId": "9c2e7a10-5b8d-4f3a-a1c6-7e0d2b4f9a58", "status": "FREE", "since": "2026-12-15T10:00:00Z", "shiftId": "e1a4b7c9-2d3f-4086-9b5a-6c7d8e9f0a12" }
```

**GET /doctors/availability**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF`
- **Request body:** none. Optional query `status` (`FREE`, `BUSY`, `OFFLINE`), `limit`, `cursor`.
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | List of `DoctorAvailability` |
| 401 / 403 | Not authenticated / not `MEDICAL_STAFF` |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "items": [ { "doctorId": "9c2e7a10-5b8d-4f3a-a1c6-7e0d2b4f9a58", "status": "FREE", "since": "2026-12-15T10:00:00Z", "shiftId": "e1a4b7c9-2d3f-4086-9b5a-6c7d8e9f0a12" } ], "nextCursor": null }
```

**POST /shifts**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF` with scope `scheduling:manage`
- **Request body:**
```json
{ "doctorId": "9c2e7a10-5b8d-4f3a-a1c6-7e0d2b4f9a58", "startsAt": "2026-12-15T08:00:00Z", "endsAt": "2026-12-15T16:00:00Z" }
```
- **Responses:**

| Status | Meaning |
|---|---|
| 201 | Shift created |
| 400 | Malformed JSON |
| 401 / 403 | Not authenticated / missing scope |
| 409 | Overlaps an existing shift of that doctor |
| 422 | `endsAt` is not after `startsAt` |
| 500 | Unexpected error |

- **Example (201):**
```json
{ "shiftId": "e1a4b7c9-2d3f-4086-9b5a-6c7d8e9f0a12", "doctorId": "9c2e7a10-5b8d-4f3a-a1c6-7e0d2b4f9a58", "startsAt": "2026-12-15T08:00:00Z", "endsAt": "2026-12-15T16:00:00Z" }
```

**GET /shifts**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF`
- **Request body:** none. Query: `from`, `to` (required), `doctorId` (optional).
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | List of `Shift` |
| 400 | Missing or invalid `from` / `to` |
| 401 / 403 | Not authenticated / not `MEDICAL_STAFF` |
| 500 | Unexpected error |

- **Example (200):**
```json
{ "items": [ { "shiftId": "e1a4b7c9-2d3f-4086-9b5a-6c7d8e9f0a12", "doctorId": "9c2e7a10-5b8d-4f3a-a1c6-7e0d2b4f9a58", "startsAt": "2026-12-15T08:00:00Z", "endsAt": "2026-12-15T16:00:00Z" } ], "nextCursor": null }
```

**POST /prescriptions**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF`, the doctor assigned to the consultation. Header `Idempotency-Key` required.
- **Request body:**
```json
{
  "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
  "items": [
    { "medicationName": "Aspirin", "dosage": "100 mg", "frequency": "once daily", "durationDays": 30, "instructions": "Take after breakfast" }
  ],
  "notes": "Follow up with cardiology within 48 hours"
}
```
- **Behavior:** in one local database transaction the service stores the prescription and writes `PrescriptionIssued` to the transactional outbox (**ADR-002**), then returns `202` immediately. Delivery to the external third-party pharmacy platform is asynchronous, eliminating both double-write inconsistencies and availability coupling so a pharmacy outage or latency spike during peak demand never blocks doctors from completing consultations.
- **Responses:**

| Status | Meaning |
|---|---|
| 202 | Registered, event will be published asynchronously |
| 400 | Malformed JSON or missing `Idempotency-Key` |
| 401 / 403 | Not authenticated / not the assigned doctor |
| 404 | Consultation not found |
| 409 | Consultation is not `IN_PROGRESS` |
| 422 | Validation error (empty `items`, invalid duration) |
| 503 | Prescription database unavailable |

- **Example (202):**
```json
{ "prescriptionId": "b7c8d9e0-f1a2-4b3c-8d4e-5f6a7b8c9d0e", "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34", "deliveryStatus": "PENDING", "issuedAt": "2026-12-15T10:45:00Z" }
```

**GET /prescriptions/{prescriptionId}**
- **Authentication:** Bearer JWT. `MEDICAL_STAFF` (issuer) or `PATIENT` (owner).
- **Request body:** none
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | `Prescription` |
| 401 / 403 | Not authenticated / not the issuer or owner |
| 404 | Prescription not found |
| 500 | Unexpected error |

- **Example (200):**
```json
{
  "prescriptionId": "b7c8d9e0-f1a2-4b3c-8d4e-5f6a7b8c9d0e",
  "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
  "patientId": "3f8a1d52-6b7c-4c0e-9a41-2e5d8c7b9f10",
  "doctorId": "9c2e7a10-5b8d-4f3a-a1c6-7e0d2b4f9a58",
  "items": [ { "medicationName": "Aspirin", "dosage": "100 mg", "frequency": "once daily", "durationDays": 30, "instructions": "Take after breakfast" } ],
  "notes": "Follow up with cardiology within 48 hours",
  "deliveryStatus": "PUBLISHED",
  "issuedAt": "2026-12-15T10:45:00Z"
}
```

**GET /consultations/{consultationId}/recording**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF` with scope `recordings:read`
- **Request body:** none
- **Behavior:** served by the independent `recording-service` (**ADR-003**), resolving the legacy bottleneck where downloading and storing video files on central servers saturated disk and network I/O and crashed the scheduling module. Returns lightweight metadata only from `recording_db` after verifying role and resource authorization (**ADR-005**). `downloadUrl` is a pre-signed object storage URL (TTL 300 s), present only when `status = AVAILABLE`, so media playback never proxies raw video bytes through MedConnect application nodes.
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | `RecordingMetadata` |
| 401 / 403 | Not authenticated / missing role or scope |
| 404 | No recording found for that consultation |
| 500 / 503 | Unexpected error / service unavailable |

- **Example (200):**
```json
{
  "recordingId": "c9d0e1f2-a3b4-4c5d-8e6f-7a8b9c0d1e2f",
  "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
  "status": "AVAILABLE",
  "url": "s3://medconnect-recordings/recordings/2026/12/7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34/c9d0e1f2.mp4",
  "durationSeconds": 2230,
  "recordedAt": "2026-12-15T10:52:10Z",
  "sizeBytes": 148230112,
  "checksumSha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "downloadUrl": "https://storage.medconnect.example/recordings/...&X-Amz-Expires=300&X-Amz-Signature=..."
}
```

**POST /webhooks/video-provider**
- **Authentication:** provider signature header `X-Provider-Signature` (HMAC-SHA256 of the raw body). No JWT.
- **Request body:**
```json
{ "type": "recording.completed", "sessionId": "abc123", "providerRecordingId": "rec_889", "durationSeconds": 2230, "downloadUrl": "https://video.provider.example/recordings/rec_889?token=..." }
```
- **Behavior:** receives asynchronous provider callbacks (`recording.completed`, `recording.failed`) once the external video provider finishes processing and uploading the recording to Object Storage (**ADR-003**). Because video processing can finish several minutes after a consultation ends, metadata persistence is triggered by this webhook rather than blocking `ConsultationCompleted`.
- **Responses:**

| Status | Meaning |
|---|---|
| 202 | Accepted |
| 400 | Malformed payload |
| 401 | Invalid signature |
| 409 | Duplicate callback already processed (no side effects) |
| 503 | Service temporarily unavailable (the provider retries) |

- **Example (202):**
```json
{ "status": "ACCEPTED", "providerRecordingId": "rec_889" }
```

**GET /audit/events**
- **Authentication:** Bearer JWT, role `MEDICAL_STAFF` with scope `audit:read`
- **Request body:** none. Query: `aggregateId`, `eventType`, `actorId`, `from`, `to`, `limit`, `cursor`. Read-only: no endpoint can modify or delete audit records.
- **Responses:**

| Status | Meaning |
|---|---|
| 200 | List of `AuditEvent` |
| 400 | Invalid filters |
| 401 / 403 | Not authenticated / missing role or scope |
| 500 | Unexpected error |

- **Example (200):**
```json
{
  "items": [
    {
      "eventId": "d1e2f3a4-b5c6-4d7e-8f90-a1b2c3d4e5f6",
      "eventType": "PriorityOverridden",
      "aggregateId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
      "actorId": "5d3c8b21-0e4f-4a77-b6d9-1c2a9e8f7b65",
      "occurredAt": "2026-12-15T10:20:00Z",
      "payload": { "previousPriority": "MEDIUM", "newPriority": "HIGH", "reason": "Patient reports radiating pain on call-back" }
    }
  ],
  "nextCursor": null
}
```

## 3. Asynchronous Events and Integration Contracts

Messaging conventions for all events:

- Broker: RabbitMQ. Domain exchange `medconnect.events` (topic, durable) buffers burst traffic (up to 500 triage requests/s during winter peaks) and decouples services. Pharmacy exchange `medconnect.pharmacy` (topic, durable), isolated from the domain exchange (**ADR-002**).
- Waiting queues: the HIGH, MEDIUM and LOW queues are **Redis sorted sets** owned by care-queue-service (member = `consultationId`, score = original `requestedAt`), per **ADR-001** and **ADR-004**. RabbitMQ transports domain events only and does not hold the waiting queues, because a broker queue only supports consuming from its head and cannot atomically move, re-rank, or remove a specific patient among 20,000 waiting entries without losing their original arrival timestamp.
- Routing key: `<aggregate>.<event>` in kebab-case. Queue name: `<consumer-service>.<event-name>`. Each consumer queue is durable and has its own dead-letter queue `<queue>.dlq`, enforcing messaging-level bulkhead isolation so a slow or failing consumer (such as `recording-service`, **ADR-003**) never backpressures triage or scheduling.
- `audit-service` consumes every event through queue `audit.all` (binding `#`).
- Delivery: at-least-once. Producers use the **transactional outbox** (**ADR-002**: the event is written in the same database transaction as the state change and then relayed by a background worker using `SELECT ... FOR UPDATE SKIP LOCKED`), preventing double-write inconsistencies. Messages are persistent and publisher confirms are enabled.
- Ordering: guaranteed per aggregate (`aggregateId`), not across aggregates. Consumers tolerate out-of-order delivery by comparing `aggregateVersion` or `triageVersion`.
- Idempotency: every consumer deduplicates by `eventId` (processed-events table). A duplicate is acknowledged and skipped.
- Retries: exponential backoff (5 s, 30 s, 2 min, 10 min, 1 h), maximum 5 attempts. After the last attempt the message goes to the consumer's DLQ and an alert is raised.
- Schema evolution: `eventVersion` field. Only backward-compatible changes (new optional fields) within a major version.

Event envelope (all events):

```yaml
EventEnvelope:
  type: object
  required: [eventId, eventType, eventVersion, occurredAt, producer, aggregateId, aggregateVersion, correlationId, payload]
  properties:
    eventId:          { type: string, format: uuid }
    eventType:        { type: string }
    eventVersion:     { type: integer, example: 1 }
    occurredAt:       { type: string, format: date-time }
    producer:         { type: string, example: consultation-service }
    aggregateId:      { type: string, format: uuid }
    aggregateVersion: { type: integer }
    correlationId:    { type: string, format: uuid }
    causationId:      { type: string, format: uuid, nullable: true }
    actorId:          { type: string, format: uuid, nullable: true }
    payload:          { type: object }
```

Example of a full message:

```json
{
  "eventId": "e5f6a7b8-c9d0-4e1f-a2b3-c4d5e6f7a8b9",
  "eventType": "ConsultationRequested",
  "eventVersion": 1,
  "occurredAt": "2026-12-15T10:15:30Z",
  "producer": "consultation-service",
  "aggregateId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
  "aggregateVersion": 1,
  "correlationId": "d4a6c0e2-7f3b-4a58-9c1d-0b2e6f8a1c33",
  "causationId": null,
  "actorId": "3f8a1d52-6b7c-4c0e-9a41-2e5d8c7b9f10",
  "payload": {
    "consultationId": "7b1f2c4e-9a3d-4e6b-8f21-5d0c9a7e1b34",
    "patientId": "3f8a1d52-6b7c-4c0e-9a41-2e5d8c7b9f10",
    "symptoms": { "mainSymptom": "CHEST_PAIN", "painLevel": 9, "symptomDuration": { "value": 30, "unit": "MINUTES" }, "age": 58, "redFlags": { "difficultyBreathing": false, "lossOfConsciousness": false }, "description": null },
    "requestedAt": "2026-12-15T10:15:30Z"
  }
}
```

**ConsultationRequested**
- **Producer:** consultation-service
- **Consumers:** triage-service, audit-service
- **Queue / topic:** exchange `medconnect.events`, routing key `consultation.requested`, queue `triage.consultation-requested`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
symptoms: SymptomReport
requestedAt: date-time
```
- **Delivery guarantees:** ordering per `consultationId`; idempotent by `eventId` (triage result is deterministic for the same input); 5 retries with backoff, then `triage.consultation-requested.dlq`. Stateless consumers scale horizontally.

**SymptomsUpdated**
- **Producer:** consultation-service
- **Consumers:** triage-service, audit-service
- **Queue / topic:** routing key `consultation.symptoms-updated`, queue `triage.symptoms-updated`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
symptoms: SymptomReport
updatedAt: date-time
```
- **Delivery guarantees:** ordering per `consultationId` is required, so a late update never overrides a newer one (`aggregateVersion`); idempotent by `eventId`; 5 retries, then DLQ.

**PatientTriaged**
- **Producer:** triage-service
- **Consumers:** care-queue-service, consultation-service, audit-service
- **Queue / topic:** routing key `triage.patient-triaged`, queues `care-queue.patient-triaged` and `consultation.patient-triaged`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
priority: HIGH | MEDIUM | LOW
ruleId: string
ruleSetVersion: string
triageVersion: integer
reason: INITIAL | SYMPTOMS_UPDATED
requestedAt: date-time      # original request time (FIFO key)
triagedAt: date-time
```
- **Delivery guarantees:** emitted for the initial triage and every automatic re-triage; ordering per `consultationId`; idempotent by `eventId`; care-queue-service inserts the patient into the Redis sorted set of the given `priority` using `requestedAt` as score, and discards the event if `triageVersion` is not greater than the stored one; 5 retries, then DLQ.

**PriorityOverridden**
- **Producer:** triage-service
- **Consumers:** care-queue-service, consultation-service, audit-service
- **Queue / topic:** routing key `triage.priority-overridden`, queues `care-queue.priority-overridden` and `consultation.priority-overridden`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
previousPriority: HIGH | MEDIUM | LOW
newPriority: HIGH | MEDIUM | LOW
triageVersion: integer
overriddenBy: uuid          # medical staff user id
reason: string
requestedAt: date-time
overriddenAt: date-time
```
- **Delivery guarantees:** audited event; ordering per `consultationId`; idempotent by `eventId`; care-queue-service applies it as an atomic move (Lua script: `triageVersion` check, remove from the old sorted set, insert into the new one keeping the original score), and stale versions are ignored; 5 retries, then DLQ.

**PatientEnqueued**
- **Producer:** care-queue-service
- **Consumers:** consultation-service (sets `WAITING`), audit-service
- **Queue / topic:** routing key `queue.patient-enqueued`, queue `consultation.patient-enqueued`
- **Payload schema:**
```yaml
consultationId: uuid
queue: HIGH | MEDIUM | LOW
previousQueue: HIGH | MEDIUM | LOW | null
reason: INITIAL | RECLASSIFIED | OVERRIDE | AGING
triageVersion: integer
requestedAt: date-time
enqueuedAt: date-time
```
- **Delivery guarantees:** emitted only after the atomic Redis move completes, so the patient is never in two queues; ordering per `consultationId`; idempotent by `eventId`; 5 retries, then DLQ.

**PatientAssigned**
- **Producer:** care-queue-service
- **Consumers:** consultation-service (sets `ASSIGNED`, creates the video session), scheduling-service (doctor becomes `BUSY`), audit-service
- **Queue / topic:** routing key `queue.patient-assigned`, queues `consultation.patient-assigned` and `scheduling.patient-assigned`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
doctorId: uuid
priority: HIGH | MEDIUM | LOW
assignedAt: date-time
```
- **Delivery guarantees:** emitted once per successful atomic claim, after the claim is confirmed (see the lease in `POST /queue/claims`); ordering per `consultationId`; idempotent by `eventId`; 5 retries, then DLQ.

**DoctorBecameAvailable**
- **Producer:** scheduling-service
- **Consumers:** care-queue-service, audit-service
- **Queue / topic:** routing key `doctor.became-available`, queue `care-queue.doctor-became-available`
- **Payload schema:**
```yaml
doctorId: uuid
shiftId: uuid
availableSince: date-time
```
- **Delivery guarantees:** ordering per `doctorId`; idempotent by `eventId`; on receipt care-queue-service marks the doctor as `FREE` in its projection; 5 retries, then DLQ.

**DoctorBecameUnavailable**
- **Producer:** scheduling-service
- **Consumers:** care-queue-service, audit-service
- **Queue / topic:** routing key `doctor.became-unavailable`, queue `care-queue.doctor-became-unavailable`
- **Payload schema:**
```yaml
doctorId: uuid
status: BUSY | OFFLINE
since: date-time
```
- **Delivery guarantees:** ordering per `doctorId`; idempotent by `eventId`; 5 retries, then DLQ.

**ConsultationStarted**
- **Producer:** consultation-service
- **Consumers:** prescription-service, audit-service
- **Queue / topic:** routing key `consultation.started`, queue `prescription.consultation-started`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
doctorId: uuid
sessionId: string
startedAt: date-time
```
- **Delivery guarantees:** ordering per `consultationId`; idempotent by `eventId`; 5 retries, then DLQ.

**ConsultationCompleted**
- **Producer:** consultation-service
- **Consumers:** scheduling-service (doctor back to `FREE`), recording-service, prescription-service, audit-service
- **Queue / topic:** routing key `consultation.completed`, queues `scheduling.consultation-completed`, `recording.consultation-completed`, `prescription.consultation-completed`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
doctorId: uuid
sessionId: string           # provider session id (no video content)
clinicalSummary: string
diagnosisCodes: [string]
startedAt: date-time
completedAt: date-time
```
- **Delivery guarantees:** each consumer has its own independent queue, so a slow or failing recording consumer never delays scheduling; ordering per `consultationId`; idempotent by `eventId`; 5 retries, then DLQ of each queue.

**ConsultationCancelled**
- **Producer:** consultation-service
- **Consumers:** care-queue-service (removes the patient from any queue), scheduling-service (frees the doctor if already `ASSIGNED`), audit-service
- **Queue / topic:** routing key `consultation.cancelled`, queues `care-queue.consultation-cancelled` and `scheduling.consultation-cancelled`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
doctorId: uuid | null
statusAtCancellation: REQUESTED | TRIAGED | WAITING | ASSIGNED
reason: string | null
cancelledAt: date-time
```
- **Delivery guarantees:** ordering per `consultationId`; idempotent (removing an absent queue entry is a no-op; later claim attempts on the same consultation are rejected); 5 retries, then DLQ.

**ConsultationNoShow**
- **Producer:** consultation-service
- **Consumers:** scheduling-service (doctor back to `FREE`), audit-service
- **Queue / topic:** routing key `consultation.no-show`, queue `scheduling.consultation-no-show`
- **Payload schema:**
```yaml
consultationId: uuid
patientId: uuid
doctorId: uuid
assignedAt: date-time
noShowAt: date-time
```
- **Delivery guarantees:** ordering per `consultationId`; idempotent by `eventId`; 5 retries, then DLQ.

**PrescriptionIssued**
- **Producer:** prescription-service (through the transactional outbox)
- **Consumers:** pharmacy platform (external; `pharmacy-provider`), audit-service
- **Queue / topic:** exchange `medconnect.pharmacy`, routing key `prescription.issued`, queue `pharmacy.prescriptions`, DLQ `pharmacy.prescriptions.dlq`
- **Payload schema:**
```yaml
prescriptionId: uuid
consultationId: uuid
patientId: uuid
doctorId: uuid
items:
  - medicationName: string
    dosage: string
    frequency: string
    durationDays: integer
    instructions: string | null
notes: string | null
issuedAt: date-time
```
- **Delivery guarantees:** published if and only if the prescription was committed; ordering per `prescriptionId`; the consumer must deduplicate by `eventId` (MedConnect may redeliver); 5 retries with exponential backoff, then `pharmacy.prescriptions.dlq` and an alert.

**RecordingAvailable**
- **Producer:** recording-service
- **Consumers:** audit-service
- **Queue / topic:** routing key `recording.available`, queue `audit.all`
- **Payload schema:**
```yaml
recordingId: uuid
consultationId: uuid
url: string                 # object storage URI (internal)
durationSeconds: integer
recordedAt: date-time
sizeBytes: integer
checksumSha256: string
```
- **Delivery guarantees:** emitted only after the file is fully stored and the metadata is committed; ordering per `consultationId`; idempotent by `eventId`; 5 retries, then DLQ.

**RecordingFailed**
- **Producer:** recording-service
- **Consumers:** audit-service, operations alerting
- **Queue / topic:** routing key `recording.failed`, queues `audit.all` and `ops.recording-failed`
- **Payload schema:**
```yaml
recordingId: uuid
consultationId: uuid
reason: string
attempts: integer
failedAt: date-time
```
- **Delivery guarantees:** emitted after the internal retry budget is exhausted; it has no effect on triage, queues, scheduling or consultations; ordering per `consultationId`; idempotent by `eventId`; 5 retries, then DLQ.

**Integration contract: Pharmacy platform (third party)**
- **Nature:** independent third-party system, treated like a payment gateway. MedConnect builds no pharmacy features and only publishes the `PrescriptionIssued` event defined above.
- **Style:** asynchronous. Exchange `medconnect.pharmacy`, routing key `prescription.issued`, queue `pharmacy.prescriptions`. The payload contains only the data the pharmacy needs (identifiers and prescription items).
- `pharmacy-provider` consumes the queue, validates the payload schema, logs `eventId` and `prescriptionId`, and acknowledges.
- **Failure handling:** pharmacy down means retries with exponential backoff (5 s, 30 s, 2 min, 10 min, 1 h). After 5 failed attempts the message moves to `pharmacy.prescriptions.dlq`, the prescription `deliveryStatus` becomes `DEAD_LETTERED`, and an alert fires. Operators can replay the DLQ after recovery.
- **Isolation:** an unavailable pharmacy never blocks prescription registration, consultations, triage or scheduling.

**Integration contract: Video provider (external, behind an Anti-Corruption Layer)**
- **Scope:** MedConnect builds no video infrastructure (Jitsi, Twilio or Daily). It stores only `sessionId` and join links.
- **Session creation:** consultation-service calls the provider when it consumes `PatientAssigned`, stores `sessionId` and issues per-participant, short-lived join URLs. Timeout 3 s, 2 retries. On failure the operation is retried, never lost.
- **Recording:** the provider records the session and notifies recording-service through `POST /webhooks/video-provider` (HMAC-signed), or recording-service polls with backoff after `ConsultationCompleted`.
- **ACL:** provider-specific models and statuses are translated at the boundary into MedConnect's `sessionId`, `joinUrl` and `RecordingMetadata`.

**Integration contract: Object storage (S3 / MinIO)**
- **Bucket:** `medconnect-recordings` (private, versioning enabled, server-side encryption). Key pattern `recordings/{yyyy}/{mm}/{consultationId}/{recordingId}.mp4`.
- **Write path:** per **ADR-003**, the external video provider uploads the recording directly to the S3-compatible bucket (or `recording-service` streams it asynchronously when direct upload is not used) and notifies `recording-service` via webhook. Recordings are never stored on the disks of business servers, eliminating the disk and network saturation that crashed the legacy scheduling module. A periodic reconciliation job backfills metadata if a provider webhook is missed.
- **Read path:** pre-signed URLs only (TTL 300 s), issued by `GET /consultations/{consultationId}/recording` after enforcing role-based and resource-level authorization (**ADR-005**).
- **Database:** only metadata (`recordingId`, `consultationId`, `url`, `durationSeconds`, `recordedAt`, `status`, `sizeBytes`, `checksumSha256`).
- **Isolation:** two-level bulkhead (**ADR-003**) with a dedicated deployment (own CPU, memory, disk and network quotas, concurrency cap per instance) and its own dedicated RabbitMQ consumer queue.

## 4. Overview: Services and Responsibilities

MedConnect is split into independent microservices following the bounded contexts of the DDD analysis, replacing the legacy centralized SQL Server monolith that coupled medical records, video recording management, and shift scheduling on the same servers. The Core Domain is triage and prioritized care queue management (`triage-service` and `care-queue-service`, **ADR-001**, **ADR-004**), designed to absorb winter peak loads (500 triage requests/s and up to 20,000 waiting patients with ~500 doctors on shift) while guaranteeing that critical patients never wait behind mild cases and that secondary workloads like video recordings (**ADR-003**) or external pharmacy delivery (**ADR-002**) never impair clinical availability.

| Service | Responsibility | Owned data store | Publishes | Consumes |
|---|---|---|---|---|
| **api-gateway** | TLS termination, JWT signature validation, rate limiting, routing, correlation-id injection. No business logic. | – | – | – |
| **identity-service** | Patient registration, login, token issuance and refresh, strict roles (`PATIENT`, `MEDICAL_STAFF`), JWKS publication. | `identity_db` | – | – |
| **consultation-service** | Owns the `Consultation` aggregate and its state machine (including `CANCELLED` and `NO_SHOW`). Receives consultation requests, handles symptom updates, cancellation, join, start and completion. Integrates with the video provider (session creation). Source of truth for consultation state. | `consultation_db` | `ConsultationRequested`, `SymptomsUpdated`, `ConsultationStarted`, `ConsultationCompleted`, `ConsultationCancelled`, `ConsultationNoShow` | `PatientTriaged`, `PriorityOverridden`, `PatientEnqueued`, `PatientAssigned` |
| **triage-service** | Rule-based automatic triage (HIGH, MEDIUM, LOW), re-triage when symptoms change, medical staff priority override. Stateless rule evaluation that scales horizontally. | `triage_db` | `PatientTriaged`, `PriorityOverridden` | `ConsultationRequested`, `SymptomsUpdated` |
| **care-queue-service** | Maintains the three priority queues as Redis sorted sets, guarantees a patient is in exactly one queue, FIFO within a level (score = original `requestedAt`), aging, and the atomic lease-based claim of the next patient by an available doctor (HIGH, then MEDIUM, then LOW). | Redis (sorted sets, working index; no relational DB) | `PatientEnqueued`, `PatientAssigned` | `PatientTriaged`, `PriorityOverridden`, `ConsultationCancelled`, `DoctorBecameAvailable`, `DoctorBecameUnavailable` |
| **scheduling-service** | Doctor shifts and availability (`FREE`, `BUSY`, `OFFLINE`). It is not a patient calendar. Its availability drives the doctor pull of the care queue. | `scheduling_db` | `DoctorBecameAvailable`, `DoctorBecameUnavailable` | `PatientAssigned`, `ConsultationCompleted`, `ConsultationCancelled`, `ConsultationNoShow` |
| **recording-service** | Independent microservice for querying consultation recordings. Receives provider callbacks, streams recordings to object storage asynchronously, stores only metadata, and serves metadata with pre-signed URLs. Fully isolated (bulkhead): its failure never affects triage or scheduling. | `recording_db` + object storage (S3 / MinIO) | `RecordingAvailable`, `RecordingFailed` | `ConsultationCompleted` |
| **prescription-service** | Registers prescriptions and publishes `PrescriptionIssued` through a transactional outbox. | `prescription_db` | `PrescriptionIssued` | `ConsultationStarted`, `ConsultationCompleted` |
| **audit-service** | Append-only store of all domain events (for example `PriorityOverridden`), for traceability and legal compliance. | `audit_db` (append-only) | – | All events (`#`) |
| **pharmacy-provider** | stand-in for the third-party pharmacy platform. Receives and logs `PrescriptionIssued`. | – | – | `PrescriptionIssued` |

Architectural rules:

- Each service owns its database. No service reads another service's tables.
- Inter-service communication is asynchronous by default (events). Synchronous calls are limited to client-facing APIs and calls to external providers through an anti-corruption layer.
- Every producer uses the transactional outbox. Every consumer is idempotent.
- The recording workload runs in separate deployments with separate resource quotas. Recordings go to object storage and never to the disks of other services.
- Waiting queues are Redis sorted sets, not broker queues. Redis is a rebuildable working index: after a loss, care-queue-service rebuilds it from the `WAITING` consultations (priority, `requestedAt`, `triageVersion`) provided by consultation-service through its snapshot or by republished events, never by reading its tables.

## 5. Requirements and Scenarios

The keywords SHALL and SHALL NOT indicate mandatory behavior.

**Feature 1 – Authentication with strict roles (Patients vs. Medical Staff)**

Enforces centralized RS256 JWT authentication and two-tier RBAC authorization—coarse-grained route checks at the API Gateway and fine-grained ownership/assignment validation via local projections inside each service—to protect sensitive medical information without synchronous cross-service calls (**ADR-005**).

- **R-F1-01:** The system SHALL authenticate users and issue JWTs containing exactly one role: `PATIENT` or `MEDICAL_STAFF`.
- **R-F1-02:** Every service SHALL validate the JWT signature and enforce role and ownership rules. A patient's identity SHALL come from the token, never from the request body.
- **R-F1-03:** Patients SHALL NOT access staff-only operations (override, claim, availability, shifts, prescriptions, recordings, audit). Staff SHALL NOT create consultations.

| ID | Scenario |
|---|---|
| S-F1-01 | **WHEN** a registered patient submits valid credentials to `POST /auth/login` **THEN** the system returns `200` with an access token whose `role` is `PATIENT`. |
| S-F1-02 | **WHEN** a user submits invalid credentials to `POST /auth/login` **THEN** the system returns `401` and issues no token. |
| S-F1-03 | **WHEN** a request without a token calls `POST /consultations` **THEN** the system returns `401`. |
| S-F1-04 | **WHEN** a `PATIENT` token calls `POST /queue/claims` or `POST /triage/consultations/{id}/override` **THEN** the system returns `403`. |
| S-F1-05 | **WHEN** a `MEDICAL_STAFF` token calls `POST /consultations` **THEN** the system returns `403`. |
| S-F1-06 | **WHEN** a patient requests `GET /consultations/{id}` of a consultation that belongs to another patient **THEN** the system returns `403`. |

**Feature 2 – Independent microservice dedicated to querying recordings**

Eliminates the extreme coupling of the legacy architecture—where downloading, processing, and storing consultation video recordings on central servers saturated network and disk I/O and crashed the scheduling module—by isolating consultation recording queries and asynchronous metadata handling in `recording-service` backed by Object Storage and two-level bulkhead isolation (**ADR-003**, **ADR-005**).

- **R-F2-01:** Consultation recordings SHALL be served by an independent service (`recording-service`) with its own deployment, resources and database.
- **R-F2-02:** Recording files SHALL be stored in object storage and processed asynchronously. Only metadata (consultation ID, URL, duration, date) SHALL be stored in the database.
- **R-F2-03:** Only `MEDICAL_STAFF` with scope `recordings:read` SHALL query recordings, and downloads SHALL use short-lived pre-signed URLs.
- **R-F2-04:** A failure or saturation of `recording-service` (network or disk) SHALL NOT affect triage, queues, scheduling or the consultation lifecycle.

| ID | Scenario |
|---|---|
| S-F2-01 | **WHEN** an authorized doctor requests `GET /consultations/{id}/recording` for an available recording **THEN** the system returns `200` with metadata and a pre-signed `downloadUrl` that expires in 300 s. |
| S-F2-02 | **WHEN** a consultation is completed **THEN** `ConsultationCompleted` is emitted, recording-service processes the recording asynchronously, stores the file in object storage and saves only metadata in its database. |
| S-F2-03 | **WHEN** a patient or a doctor without the `recordings:read` scope requests a recording **THEN** the system returns `403`. |
| S-F2-04 | **WHEN** recording-service fails or saturates network or disk **THEN** `RecordingFailed` is emitted after the retries, and triage, queues and scheduling keep working normally. |
| S-F2-05 | **WHEN** the video provider delivers the same webhook twice **THEN** the second delivery returns `409` and no duplicate recording is created. |

**Feature 3 – Automatic Triage Engine with prioritized asynchronous queues**

Replaces the legacy centralized SQL Server FIFO table that collapsed under winter peaks (500 triage requests/s and 20,000 waiting patients) by decoupling consultation intake via the Transactional Outbox (**ADR-002**), evaluating symptoms deterministically in a horizontally scalable `triage-service`, and maintaining three separate priority queues (`HIGH`, `MEDIUM`, `LOW`) as Redis sorted sets (**ADR-001**).

- **R-F3-01:** `POST /consultations` SHALL respond with `202` and process triage asynchronously, absorbing bursts of up to 500 requests per second.
- **R-F3-02:** The triage engine SHALL classify each request as HIGH, MEDIUM or LOW with the following rule set `v1`, evaluated in order (the first match wins). The engine is rule-based (no AI/ML) and the free-text `description` is never used for classification.

| # | Condition | Priority | `ruleId` |
|---|---|---|---|
| 1 | `redFlags.difficultyBreathing = true` or `redFlags.lossOfConsciousness = true` | HIGH | `R1_RED_FLAG` |
| 2 | `mainSymptom` is `CHEST_PAIN` or `SHORTNESS_OF_BREATH` | HIGH | `R2_CRITICAL_SYMPTOM` |
| 3 | `mainSymptom = FEVER` and `painLevel >= 7` | MEDIUM | `R3_FEVER_HIGH_PAIN` |
| 4 | Anything else | LOW | `R4_DEFAULT` |

- **R-F3-03:** The system SHALL maintain three separate priority queues (HIGH, MEDIUM, LOW), implemented as Redis sorted sets fed asynchronously by triage events, and a patient SHALL be in exactly one queue at any time.
- **R-F3-04:** Medical staff SHALL be able to override the priority. The override SHALL emit `PriorityOverridden` and be audited.
- **R-F3-05:** When symptoms are updated or the patient is reclassified, the patient SHALL move atomically to the new queue. Events with a lower `triageVersion` SHALL be ignored.

| ID | Scenario |
|---|---|
| S-F3-01 | **WHEN** a patient submits `mainSymptom = CHEST_PAIN` **THEN** the system responds `202`, triage assigns `HIGH` (`R2_CRITICAL_SYMPTOM`) and the patient is enqueued in the HIGH queue. |
| S-F3-02 | **WHEN** a patient submits `difficultyBreathing = true` with `mainSymptom = COUGH` **THEN** triage assigns `HIGH` (`R1_RED_FLAG`). |
| S-F3-03 | **WHEN** a patient submits `FEVER` with `painLevel = 8` **THEN** triage assigns `MEDIUM` (`R3_FEVER_HIGH_PAIN`). |
| S-F3-04 | **WHEN** a patient submits `COUGH` with `painLevel = 2` and no red flags **THEN** triage assigns `LOW` (`R4_DEFAULT`). |
| S-F3-05 | **WHEN** a patient with an active consultation calls `POST /consultations` again **THEN** the system returns `409`. |
| S-F3-06 | **WHEN** 500 triage requests per second arrive during a winter peak **THEN** the API keeps answering `202`, requests are buffered by the broker, and triage consumers scale horizontally without losing any request. |
| S-F3-07 | **WHEN** a doctor overrides a `WAITING` MEDIUM patient to `HIGH` with a reason **THEN** `PriorityOverridden` is emitted, the patient moves atomically from MEDIUM to HIGH, and the audit service records the actor and the reason. |
| S-F3-08 | **WHEN** a patient updates symptoms and the new rule result is a different priority **THEN** the patient moves to the new queue and is never present in two queues. |
| S-F3-09 | **WHEN** an override is requested for a consultation in `IN_PROGRESS` **THEN** the system returns `409`. |
| S-F3-10 | **WHEN** `PatientTriaged` and `PriorityOverridden` for the same consultation arrive out of order **THEN** care-queue-service applies the one with the higher `triageVersion` and ignores the stale one. |

**Feature 4 – Doctor consumption mechanism respecting the highest criticality first**

Solves the critical clinical failure where a patient suffering a heart attack (severe chest pain) waited hours behind 200 mild cold cases. Available (`FREE`) doctors among the ~500 on shift pull patients atomically in strict criticality order (`HIGH` → `MEDIUM` → `LOW`) with fair FIFO ordering by original `requestedAt` within each level and bounded waiting for `LOW` patients via threshold-based aging to `MEDIUM` (**ADR-001**, **ADR-004**).

- **R-F4-01:** An available doctor SHALL extract patients in strict order HIGH, then MEDIUM, then LOW.
- **R-F4-02:** Within the same priority, patients SHALL be attended in arrival order (FIFO by `requestedAt`).
- **R-F4-03:** A patient SHALL be claimed by exactly one doctor (atomic claim with a lease). Only doctors with status `FREE`, managed by scheduling-service, SHALL be able to claim. An unconfirmed claim SHALL return the patient to the queue when the lease expires.
- **R-F4-04:** Low-priority patients SHALL NOT wait indefinitely: a patient in LOW longer than the aging threshold (default 90 minutes, documented in an ADR) is promoted to MEDIUM keeping the original `requestedAt`. Aging SHALL NOT promote to HIGH.
- **R-F4-05:** If an assigned patient does not connect within the timeout (default 300 s), the consultation SHALL become `NO_SHOW` and the doctor SHALL return to `FREE`.
- **R-F4-06:** Consultation state transitions SHALL follow `REQUESTED -> TRIAGED -> WAITING -> ASSIGNED -> IN_PROGRESS -> COMPLETED`, with `CANCELLED` and `NO_SHOW` as exits. Any other transition SHALL be rejected with `409`.

| ID | Scenario |
|---|---|
| S-F4-01 | **WHEN** a `FREE` doctor calls `POST /queue/claims` and the queues hold 200 LOW patients and 1 HIGH patient **THEN** the HIGH patient is returned (`201`) first. |
| S-F4-02 | **WHEN** HIGH is empty and MEDIUM has patients **THEN** the doctor receives the oldest MEDIUM patient. |
| S-F4-03 | **WHEN** two patients with the same priority are waiting **THEN** the one with the earlier `requestedAt` is assigned first. |
| S-F4-04 | **WHEN** two doctors claim at the same time and only one patient is waiting **THEN** one receives `201` and the other receives `204`, and the patient is never assigned twice. |
| S-F4-05 | **WHEN** all queues are empty **THEN** `POST /queue/claims` returns `204`. |
| S-F4-06 | **WHEN** a `BUSY` or `OFFLINE` doctor calls `POST /queue/claims` **THEN** the system returns `409` and no patient is removed from any queue. |
| S-F4-07 | **WHEN** a doctor is assigned a patient **THEN** `PatientAssigned` is emitted, the consultation becomes `ASSIGNED` and the doctor becomes `BUSY`. |
| S-F4-08 | **WHEN** a patient has waited in LOW longer than the aging threshold **THEN** the patient moves to MEDIUM keeping the original `requestedAt` and `PatientEnqueued` is emitted with `reason = AGING`. |
| S-F4-09 | **WHEN** an assigned patient does not call `/join` within the no-show timeout **THEN** the consultation becomes `NO_SHOW`, `ConsultationNoShow` is emitted and the doctor returns to `FREE`. |
| S-F4-10 | **WHEN** a patient cancels while `WAITING` **THEN** the patient is removed from the queue, the status becomes `CANCELLED` and the patient cannot be claimed afterward. |
| S-F4-11 | **WHEN** the scheduling data of a doctor changes (shift started or consultation completed) **THEN** `DoctorBecameAvailable` is emitted and the doctor can pull the next patient. |
| S-F4-12 | **WHEN** a claim is not confirmed (the service fails before `PatientAssigned` is committed) and the lease expires **THEN** the patient returns to their original queue with their original `requestedAt` and is not lost. |

**Feature 5 – Asynchronous registration of prescriptions emitting an event to an external pharmacy platform**

Decouples prescription issuance from the independent third-party pharmacy system using the Transactional Outbox pattern (**ADR-002**), preventing external pharmacy latency or downtime from blocking doctors and avoiding double-write data loss during peak traffic.

- **R-F5-01:** Prescription registration SHALL NOT depend synchronously on the pharmacy platform.
- **R-F5-02:** `PrescriptionIssued` SHALL be published if and only if the prescription was persisted (transactional outbox).
- **R-F5-03:** The event contract SHALL follow Section 3. Delivery SHALL use retries with backoff and a dead-letter queue. A mock consumer that receives and logs the event is sufficient.
- **R-F5-04:** A prescription SHALL NOT be a consultation state. It SHALL be a separate `PrescriptionIssued` event.

| ID | Scenario |
|---|---|
| S-F5-01 | **WHEN** the assigned doctor posts a valid prescription during an `IN_PROGRESS` consultation **THEN** the system responds `202`, stores the prescription and emits `PrescriptionIssued` through the outbox. |
| S-F5-02 | **WHEN** `PrescriptionIssued` is published **THEN** the mock pharmacy consumer receives and logs it, and the prescription `deliveryStatus` becomes `PUBLISHED`. |
| S-F5-03 | **WHEN** the pharmacy platform is down **THEN** prescription registration still returns `202`, delivery is retried with exponential backoff and the consultation workflow is unaffected. |
| S-F5-04 | **WHEN** delivery fails after 5 attempts **THEN** the message moves to `pharmacy.prescriptions.dlq`, `deliveryStatus` becomes `DEAD_LETTERED` and an alert is raised. |
| S-F5-05 | **WHEN** the broker redelivers the same `PrescriptionIssued` (same `eventId`) **THEN** the consumer processes it once and acknowledges the duplicate without side effects. |
| S-F5-06 | **WHEN** a doctor who is not assigned to the consultation posts a prescription **THEN** the system returns `403` and no event is emitted. |
| S-F5-07 | **WHEN** a prescription is posted for a consultation that is not `IN_PROGRESS` **THEN** the system returns `409`. |
| S-F5-08 | **WHEN** the same prescription request is retried with the same `Idempotency-Key` **THEN** the original response is returned and only one event is emitted. |