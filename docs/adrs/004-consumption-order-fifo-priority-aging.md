# ADR-004: Consumption Order: Strict Priority, FIFO per Level and Aging

- **Status:** Approved
- **Date:** 2026-10-16
- **Deciders:** Team Three

## Context and Problem

ADR-001 defines the three queues (HIGH, MEDIUM, LOW) as Redis sorted sets, but the order in which doctors consume them still needs an explicit policy. In the legacy system, a single FIFO table put a patient with severe chest pain behind 200 mild cases. Two questions follow:

- **Inside one level, which patient goes first?** Patients with the same priority are attended in arrival order (FIFO), for example, among HIGH-priority patients.
- **What about patients in LOW?** With strict priority, during the winter peak (20,000 waiting patients, about 500 doctors), new HIGH and MEDIUM patients can keep arriving, and a LOW patient could wait indefinitely. Aging is an optional improvement to address this problem.

We need a consumption order that serves the most critical patients first, is fair within each level, and prevents indefinite waiting without delaying HIGH-priority patients.

## Considered Alternatives

- **Alternative 1: Strict Priority and FIFO without Aging**

  The simplest option. It never delays a critical patient, but LOW patients can wait indefinitely when the higher levels remain busy.

- **Alternative 2: Proportional Service between Levels**

  For example, attend one LOW patient after every few HIGH or MEDIUM patients. This avoids starvation, but a mild case could sometimes be served before a critical one, which goes against the purpose of the feature.

- **Alternative 3: One Dynamic Score Mixing Priority and Waiting Time**

  A single sorted set where the score grows with waiting time. It ages patients continuously, but the order depends on a formula that is difficult to explain, audit, and tune. A mild case could eventually outrank a HIGH-priority patient.

## Decision

Combine **strict priority between levels**, **FIFO within each level**, and **threshold-based aging from LOW to MEDIUM**, using the Redis sorted sets and the atomic scripts already defined in ADR-001.

- **Between levels:** The atomic claim script takes the oldest patient from HIGH, then MEDIUM, and finally LOW. A lower level is only read when the higher-priority queues are empty.

- **Inside a level:** The score of every entry is the original `requestedAt` (in milliseconds), so `ZPOPMIN` always returns the patient who arrived first. Reclassification, lease recovery, and aging preserve this original score and never reset it.

- **Aging:** A patient who has waited longer than the aging threshold (90 minutes by default, configurable as defined in `spec.md`) moves from LOW to MEDIUM. Patients are never promoted to HIGH, which remains reserved for clinical criticality determined by triage or staff.

- **How aging runs:** A periodic job queries the LOW sorted set for entries whose scores are older than the current time minus the threshold, using a range query by score. It promotes each eligible patient with the same Lua script used for reclassification: check `triageVersion`, then execute `ZREM` and `ZADD` with the same score.

- **Priority of the clinical decision:** A subsequent re-triage or staff override carries a newer `triageVersion`, so it takes precedence over an aging promotion.

- **Traceability:** Each promotion emits `PatientEnqueued` with `reason = AGING`, so the audit log records why the patient's priority level changed.

Because the original score is preserved, a promoted patient is placed behind older MEDIUM patients and ahead of newer ones, maintaining arrival order within MEDIUM.

## Trade-offs

- **Positives (What we gain):**
  - **Clinical Prioritization Preserved:** Aging never reaches HIGH, so critical patients are never delayed by this mechanism.
  - **Fairness Within Each Level:** FIFO based on the original `requestedAt` is maintained after reclassification, lease recovery, and promotion.
  - **Bounded Waiting:** A LOW patient waits for the aging threshold plus the time spent in MEDIUM, rather than remaining in LOW indefinitely.
  - **Cheap and Simple:** Aging requires one range query by score and the existing move script, without introducing new data structures.
  - **Traceable and Adjustable:** The threshold is configurable, and every promotion is audited.

- **Negatives (Risks / What we lose):**
  - **Wrong Threshold:** If it is too short, it erases the distinction between levels; if it is too long, waiting times remain high. *Mitigation:* Make it configurable, monitor the longest wait per level, and tune it using real data.
  - **Extra Load on MEDIUM:** During a peak, promoted patients increase the MEDIUM queue depth. *Mitigation:* Use a relatively long default threshold (90 minutes), so only patients experiencing prolonged waits are moved, and configure alerts for MEDIUM queue depth.
  - **Aging Job as a Moving Part:** If the job stops, LOW patients can experience starvation again. *Mitigation:* Promotions are idempotent, allowing the job to rerun safely, and an alert is triggered when the oldest LOW entry exceeds the threshold plus an additional margin.
  - **Race with a Re-triage:** A promotion and a clinical change could occur simultaneously. *Mitigation:* The `triageVersion` check inside the script discards the stale operation.
  - **Waiting Is Bounded, Not Necessarily Short:** A LOW patient can still wait a long time. *Mitigation:* The threshold can be lowered during the winter season if necessary.

## Final Justification

This combination preserves the central rule of the feature: the most critical patient must be attended to first. It also addresses the main weakness of strict priority, which is starvation, at a very low cost.

FIFO based on the original `requestedAt` preserves fairness even when patients move between levels. Reusing the sorted-set score and the atomic move script from ADR-001 avoids introducing additional technology to operate. Finally, the audit trail through `PatientEnqueued` with `reason = AGING` makes every promotion traceable and explainable.