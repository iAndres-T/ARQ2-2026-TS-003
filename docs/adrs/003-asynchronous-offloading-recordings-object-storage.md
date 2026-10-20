# ADR-003: Asynchronous Offloading of Video Consultation Recordings to Object Storage

- **Status:** Approved
- **Date:** 2026-10-18
- **Deciders:** Team Three

## Context and Problem

In the legacy architecture, the servers running the scheduling and consultation modules also download, process, and store video recordings on local disk. During peak periods (up to 500 triage requests/s and 20,000 waiting patients), these video processing tasks saturate network bandwidth and disk I/O, causing the entire scheduling module to crash and leaving doctors unable to work. The system requires video recording for legal and medical reasons, but recording tasks must be completely isolated so they never impair core clinical availability, triage, or doctor scheduling.

## Considered Alternatives

- **Alternative 1: Synchronous Processing on Main Web Servers (Legacy)**
  Processes videos directly on the application nodes. Saturates CPU, disk I/O, and network bandwidth on primary nodes, leading to cascading failures that crash the scheduling service.
- **Alternative 2: Storing Video Files as BLOBs in Central Database (SQL Server)**
  Stores binary video files inside database tables. Severely inflates database size, increases backup/restore times, degrades query performance, and exacerbates database bottlenecking during peak traffic.
- **Alternative 3: Storing Videos on Local Disks with Dedicated Worker Nodes**
  Uses separate worker servers but saves files to local disks. Lacks horizontal scalability, introduces file persistence risks if worker nodes fail or auto-scale down, and complicates file access across multi-instance deployments.
- **Alternative 4: Asynchronous Offloading to Object Storage (S3 / MinIO)**
  Decouples video processing via events, uploads files to external Object Storage, and persists only lightweight metadata in the database.

## Decision

Isolate recording management into a dedicated **Recording Service** and decouple it entirely from the main API and Scheduling services via **Asynchronous Event-Driven Processing** and **Object Storage (S3 / MinIO)**.

- **External Video Provider:** WebRTC/Video connections are established via external providers (e.g., Twilio or Daily). MedConnect only creates the session and stores the session ID/link.
- **Direct Provider-to-Storage Upload (No Download by MedConnect):** The provider records the consultation and uploads the file directly to Object Storage (S3 / MinIO) — Twilio and Daily both support recording straight to an S3-compatible bucket. The Recording Service never downloads or proxies the raw media file; it only receives a notification once the file exists in storage. This keeps isolation real: no recording bytes ever transit through a MedConnect-operated node.
- **Metadata Triggered by the Provider's "Recording Ready" Webhook, Not `ConsultationCompleted`:** Recording processing can finish several minutes after the consultation ends, so the Recording Service persists metadata when the provider's asynchronous "recording ready" webhook arrives, carrying the object location and duration. `ConsultationCompleted` (consumed for correlation/bookkeeping) is not a reliable trigger, since the file may not exist in storage yet when that event fires.
- **Metadata Persistence:** Only lightweight metadata (`consultationId`, `objectKey`, `duration`, `recordedAt`) is stored in the database. The object itself is never embedded or proxied through application code.
- **Authorized Playback via Presigned URLs:** Recordings are played back or downloaded through short-lived, presigned Object Storage URLs issued by the Recording Service on request. Before issuing a URL, the Recording Service enforces role-based authorization per **ADR-005** (e.g., the owning patient, or medical staff currently or previously assigned to the consultation) — unauthorized requests never receive a URL.
- **Bulkhead Pattern (Process and Messaging Isolation):** Isolation is enforced at two levels, not just the container/pod:
  - **Compute:** The Recording Service runs in its own container/pod with its own CPU, memory, and concurrency limits, independent of the triage and scheduling services.
  - **Messaging:** The Recording Service consumes from a **dedicated RabbitMQ queue (or vhost)**, separate from the queues used by triage and scheduling. A backlog or slowdown in recording-related messages (e.g., webhook retries, dead-letter traffic) fills only its own queue/vhost and never the broker resources that triage and scheduling events depend on.

## Trade-offs

- **Positives (What we gain):**
  - **True Fault Isolation:** Since recording bytes never pass through a MedConnect-operated node, a failure, network bottleneck, or spike in recording volume cannot saturate the bandwidth, disk I/O, or messaging broker that triage and doctor scheduling depend on.
  - **Resource Optimization:** Primary application servers remain lightweight and responsive, while Object Storage provides cost-effective, durable, and highly scalable media storage.
  - **Independent Scalability:** The Recording Service can scale horizontally based on its own queue/vhost depth without over-provisioning core API nodes.
  - **Controlled Access:** Presigned URLs scoped by role (ADR-005) prevent unauthorized playback or download while avoiding the Recording Service acting as a bandwidth-consuming proxy for media.
- **Negatives (Risks / What we lose):**
  - **Eventually Consistent Metadata:** Metadata is only persisted once the provider's "recording ready" webhook arrives, which can be several minutes after `ConsultationCompleted`, so a recording is not immediately available when the consultation ends.
  - **Dependence on Provider Webhook Delivery:** If the provider fails to deliver or retry the "recording ready" webhook, metadata is never persisted even though the file exists in storage. *Mitigation:* a reconciliation job that periodically lists recent objects in storage and backfills missing metadata.
  - **Operational Complexity:** Requires managing an object storage infrastructure (MinIO/S3), a dedicated queue/vhost and resource limits for the Recording Service, and background retry policies (dead-letter queues) for failed webhook processing.

## Final Justification

Offloading video recordings asynchronously to Object Storage is the optimal choice because it directly resolves the extreme coupling and resource exhaustion that caused scheduling crashes in the legacy system. Having the external provider upload recordings directly to Object Storage — instead of the Recording Service downloading and re-uploading them — avoids reintroducing that same coupling inside a new boundary. Driving metadata persistence from the provider's "recording ready" webhook, isolating the Recording Service's messaging on its own queue/vhost with its own resource limits, and gating playback behind role-authorized presigned URLs together ensure MedConnect keeps high availability for critical clinical operations (triage and scheduling) regardless of video processing volume.