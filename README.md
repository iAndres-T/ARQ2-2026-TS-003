# MedConnect

Telemedicine platform redesigned from a legacy SQL Server monolith into event-driven microservices: patients request a consultation, rule-based triage puts them in a prioritized queue, and free doctors pull the next patient.
The contract is [`docs/spec.md`](docs/spec.md); the design rationale is in [`docs/adrs/`](docs/adrs/) (ADR-001..007), the C4 container diagram in `docs/assets/`, and every place where the implementation extends or deviates from the spec in [`docs/spec-implementation-notes.md`](docs/spec-implementation-notes.md).

## Prerequisites

- Docker Desktop (Compose v2) with about 8 GB of memory for the whole stack.
- Java 21 only to build or run the tests outside Docker (the Maven wrapper `./mvnw` is included).
- For the smoke scripts: bash, curl, GNU `date` (Git Bash on Windows, any Linux shell) and **[jq](https://jqlang.org), which is required** (Windows: `winget install jqlang.jq`).

## Quick start

```bash
docker compose up -d --build      # first build takes a few minutes; wait until all services are healthy
./scripts/e2e-smoke.sh            # 12-step happy path through the gateway, ends with "ALL STEPS PASSED"
./scripts/e2e-isolation.sh        # S-F2-04: stops recording-service + recording-db, runs steps 1-10, restarts them
```

`./scripts/e2e-smoke.sh --until N` runs only steps 1..N. The smoke script is re-runnable: it registers a new patient each time and, on exit, cancels or completes its own consultation so doctor1 is `FREE` again. It refuses to start if other patients are already waiting in the `HIGH` queue (claims always pop the oldest one); such leftovers are released by the no-show timer (300 s) after a doctor claims them.

### HTTPS and the development certificate

The API base URL is **https://localhost:8443/api/v1**. On first start the one-shot `gateway-cert` container generates a self-signed certificate (SAN `localhost`, `api-gateway`, `127.0.0.1`) into the `gateway-certs` volume; no key is committed. The scripts fetch it automatically (`BASE_URL` defaults to `https://localhost:8443`). For manual calls:

```bash
docker compose cp api-gateway:/certs/gateway.crt ./gateway.crt
curl --cacert gateway.crt https://localhost:8443/api/v1/.well-known/jwks.json   # or curl -k for a quick test
```

If the `gateway-certs` volume is recreated, restart `video-provider` (it loads the certificate at startup to call the webhook over HTTPS).

## Services

Inside the compose network every service listens on `8080` (plain HTTP); only the gateway (HTTPS) and the tooling UIs are published.

| Module | Role | Datastore |
|---|---|---|
| `api-gateway` | single entry point: TLS, JWT check, coarse RBAC, rate limit | Redis (rate limit) |
| `identity-service` | register / login / refresh, RS256 JWT, JWKS | `identity-db` |
| `consultation-service` | consultation state machine, video ACL | `consultation-db` + Redis cache |
| `triage-service` | rule set `v1`, re-triage, override | `triage-db` |
| `care-queue-service` | HIGH / MEDIUM / LOW sorted sets, claim + lease, aging | Redis |
| `scheduling-service` | shifts, doctor availability | `scheduling-db` |
| `prescription-service` | prescriptions, `PrescriptionIssued` to the pharmacy | `prescription-db` |
| `recording-service` | provider webhook, recording metadata, pre-signed URLs (bulkhead) | `recording-db` + MinIO |
| `audit-service` | append-only store of every event | `audit-db` |
| `pharmacy-provider`, `video-provider` | mocks of the external pharmacy and video provider | – |

| Published port | URL | What |
|---|---|---|
| 8443 | https://localhost:8443/api/v1 | API Gateway (HTTPS, self-signed dev certificate) |
| 3000 | http://localhost:3000 | Grafana (admin / admin, anonymous Viewer) |
| 16686 | http://localhost:16686 | Jaeger |
| 15672 | http://localhost:15672 | RabbitMQ management (medconnect / medconnect) |
| 9001 | http://localhost:9001 | MinIO console (medconnect / medconnect-secret) |
| 9000 | http://localhost:9000 | MinIO S3 API, used by the pre-signed recording URLs |

The gateway's plain port `8080` (actuator health and Prometheus only) is not published.

## Configuration

Defaults are local test values. Copy `.env.example` to `.env` to override the variables below; everything else has a default in each service's `application.yml` (env-var placeholders) and can be set in `docker-compose.yml`.

| Variable | Default | Used by |
|---|---|---|
| `IDENTITY_DB_PASSWORD` … `RECORDING_DB_PASSWORD` | service name | each PostgreSQL container and its service |
| `AUDIT_APP_PASSWORD` | `audit_app` | audit-service runtime role (INSERT/SELECT only) |
| `RABBITMQ_PASSWORD` | `medconnect` | services' broker password — **must match the hash in `infra/rabbitmq/definitions.json`**, see below |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | `medconnect` / `medconnect-secret` | MinIO, recording-service, video-provider |
| `MINIO_PUBLIC_URL` | `http://localhost:9000` | host part of pre-signed URLs |
| `INTERNAL_TOKEN` | `dev-internal-token` | care-queue → consultation internal snapshot |
| `VIDEO_WEBHOOK_SECRET` | `dev-video-webhook-secret` | webhook HMAC (video-provider signs, recording-service verifies) |
| `RECORDING_MODE` | `completed` | video-provider mock: `completed` or `failed` |
| `GATEWAY_KEYSTORE_PASSWORD` | `changeit` | dev TLS keystore |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | `admin` / `admin` | Grafana |
| `ACCESS_TOKEN_TTL`, `REFRESH_TOKEN_TTL`, `MAX_FAILED_LOGINS`, `LOCKOUT_DURATION` | `3600s`, `7d`, `5`, `15m` | identity-service |
| `MEDCONNECT_QUEUE_AGING_THRESHOLD`, `MEDCONNECT_QUEUE_LEASE`, `MEDCONNECT_NO_SHOW_TIMEOUT` | `90m`, `30s`, `300s` | care-queue / consultation |
| `RECORDING_PROCESSING_TIMEOUT` | `30m` | recording-service timeout job |
| `RATE_LIMIT_REPLENISH_RATE`, `RATE_LIMIT_BURST_CAPACITY` | `20`, `40` | gateway rate limit per user |
| `DB_CONNECTION_TIMEOUT` (ms) | `3000` | JDBC services |
| `DB_POOL_SIZE` | `20` | triage, consultation, audit (others: Hikari default 10; recording `RECORDING_DB_POOL` = 5) |
| `LISTENER_CONCURRENCY`, `LISTENER_MAX_CONCURRENCY` | `2`, `4` (care-queue `8`) | RabbitMQ listeners |

**RabbitMQ credentials:** the broker user `medconnect` is created by `infra/rabbitmq/definitions.json` with a fixed password hash, and `docker-compose.yml` hard-codes the user name. `RABBITMQ_USER` is not read, and changing `RABBITMQ_PASSWORD` alone breaks every service. To change the password, update the hash in `definitions.json` as well (it is loaded at every broker start) and restart `rabbitmq` and the services.

## Seed accounts (test credentials)

Seeded by `identity-service/src/main/resources/db/migration/V2__seed_medical_staff.sql`; password `Doctor!Passw0rd1` for all three.

| Email | doctorId | Scopes |
|---|---|---|
| doctor1@medconnect.test | `d0c70000-0000-4000-8000-000000000001` | `scheduling:manage`, `recordings:read`, `audit:read` |
| doctor2@medconnect.test | `d0c70000-0000-4000-8000-000000000002` | – |
| doctor3@medconnect.test | `d0c70000-0000-4000-8000-000000000003` | `scheduling:manage` |

Patients register themselves with `POST /api/v1/auth/register`.

## Tests

```bash
./mvnw verify                              # whole reactor (Testcontainers: Docker must be running)
./mvnw -pl triage-service -am test         # one service plus medconnect-common
```

Integration tests start their own PostgreSQL / Redis / RabbitMQ containers and never use the running compose stack.

## Observability

- **Traces:** Jaeger (http://localhost:16686). Every service exports OTLP; the outbox stores `traceparent` (D12), so one trace spans HTTP → outbox → RabbitMQ → consumers.
- **Logs:** JSON (ECS) on stdout, collected by Grafana Alloy into Loki. In Grafana → Explore → Loki: `{container="triage-service"} | json | trace_id="<id>"`; the `trace_id` field links to Jaeger.
- **Metrics:** Prometheus scrapes every service's `/actuator/prometheus`, RabbitMQ and Redis (`redis-exporter`), internal only, used through Grafana. Dashboard *MedConnect overview* in Grafana.
- **Alerts** (`infra/prometheus/alerts.yml`): `DeadLetterQueueNotEmpty`, `LowPriorityWaitTooLong`, `OutboxBacklogHigh`, `OutboxUnroutableEvents`, `RedisMemoryHigh`, `MediumQueueDepthHigh`.

## DLQ replay runbook

After the last retry (1 delivery + 5 retries, about 73 min in total) a failing message lands in `<queue>.dlq` and `DeadLetterQueueNotEmpty` fires. Nothing replays it automatically. Once the cause is fixed (dependency back up, bug deployed):

1. Inspect the messages: RabbitMQ UI → Queues → `<queue>.dlq` → *Get messages* (requeue on). The `x-exception-message` header holds the last error and `x-final-attempts` the delivery count.
2. Enable the shovel plugins (not enabled by default): add them to `infra/rabbitmq/enabled_plugins` so it reads `[rabbitmq_management,rabbitmq_prometheus,rabbitmq_shovel,rabbitmq_shovel_management].`, then `docker compose restart rabbitmq` (queues and persistent messages survive the restart).
3. Move the messages back to the original queue, either:
   - **UI:** Queues → `<queue>.dlq` → *Move messages* → destination queue `<queue>` (the shovel deletes itself when the DLQ is empty), or
   - **CLI** (`rabbitmqadmin` v2 ships in the image):
     ```bash
     docker compose exec rabbitmq rabbitmqadmin -u medconnect -p medconnect shovels declare_amqp091 \
       --name replay-<queue> --source-uri amqp://medconnect:medconnect@localhost --source-queue <queue>.dlq \
       --destination-uri amqp://medconnect:medconnect@localhost --destination-queue <queue>
     # when the DLQ is empty:
     docker compose exec rabbitmq rabbitmqadmin -u medconnect -p medconnect shovels delete --name replay-<queue>
     ```
     This shovel does not stop by itself: delete it once the DLQ is empty, or messages that fail again would loop.
4. Watch the consumer logs; a message that fails again restarts the full retry chain (the DLQ copy carries no attempt counter) and returns to the DLQ.

**Why replay is safe:** every consumer deduplicates by `eventId` (inbox table, Redis inbox keys or audit primary key) and discards stale state with `aggregateVersion` / `triageVersion` checks, so a replayed event that was already applied, or that is older than the current state, is a no-op. Exception: care-queue keeps cancellation tombstones for 24 h, so replaying a `PatientTriaged` more than 24 h after its consultation was cancelled could re-enqueue it; check the consultation status first.

**`pharmacy.prescriptions.dlq`:** messages arrive through the fanout `medconnect.pharmacy.dlx`, which also notifies prescription-service (`prescription.delivery-failed`) and sets `deliveryStatus = DEAD_LETTERED`. Replay to `pharmacy.prescriptions` once the pharmacy is reachable (mock: `pharmacy-provider` outage toggle off). The prescription keeps `DEAD_LETTERED` after a successful replay (no acknowledgement event exists). The mock pharmacy deduplicates `eventId` in memory only, so a replay after a pharmacy restart can deliver a prescription twice.
