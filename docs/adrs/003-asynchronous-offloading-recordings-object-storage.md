# ADR-003: Asynchronous Offloading of Video Consultation Recordings to Object Storage

- **Status:** Approved
- **Date:** 2026-10-12
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

- **External Video Provider:** WebRTC/Video connections are established via external providers (e.g., Jitsi, Twilio, or Daily). MedConnect only creates the session and stores the session ID/link.
- **Asynchronous Execution:** Upon consultation completion, the Consultation service emits a `ConsultationCompleted` event. The Recording Service consumes this event asynchronously to fetch or receive the video file.
- **Object Storage:** Recording files are uploaded directly to Object Storage (S3 / MinIO) instead of local application server disks.
- **Metadata Persistence:** Only lightweight metadata (`consultationId`, `objectUrl`, `duration`, `recordedAt`) is stored in the database.
- **Bulkhead Pattern:** Resource usage for downloading and uploading recordings is strictly isolated to the Recording Service container/pod, ensuring zero impact on triage and scheduling workflows.

## Trade-offs

- **Positives (What we gain):**
  - **Fault Isolation:** A failure, network bottleneck, or spike in recording processing never affects triage, queue consumption, or doctor scheduling.
  - **Resource Optimization:** Primary application servers remain lightweight and responsive, while Object Storage provides cost-effective, durable, and highly scalable media storage.
  - **Independent Scalability:** The Recording Service can scale horizontally based on background job queue depth without over-provisioning core API nodes.
- **Negatives (Risks / What we lose):**
  - **Eventually Consistent Metadata:** Video recordings and their metadata become available shortly after the consultation ends rather than instantaneously.
  - **Operational Complexity:** Requires managing an object storage infrastructure (MinIO/S3) and handling background retry policies (dead-letter queues) for failed media transfers.

## Final Justification

Offloading video recordings asynchronously to Object Storage is the optimal choice because it directly resolves the extreme coupling and resource exhaustion that caused scheduling crashes in the legacy system. By isolating recording tasks behind an event consumer and leveraging MinIO/S3, MedConnect ensures high availability for critical clinical operations (triage and scheduling) regardless of video processing volume.