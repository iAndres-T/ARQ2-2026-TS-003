# ADR-004: Safe Patient Assignment (One Patient, One Doctor)

- **Status:** Approved
- **Date:** 2026-10-12
- **Deciders:** Team Three

- **Context and Problem:** When doctors become available, hundreds of them can ask for their next patient at almost the same moment. Without care, three things can go wrong:
  - two doctors receive the same patient;
  - a doctor repeats a request (frozen screen, network failure) and ends up with two patients;
  - a patient is assigned but never connects, leaving the doctor waiting.

  We need an assignment that gives each patient to exactly one doctor, is safe to repeat and has a time limit.

- **Considered Alternatives:**
  - **Alternative 1 – Check first, then assign (two separate steps):** simple, but if two doctors ask at the same instant, both can pass the check and receive the same patient.
  - **Alternative 2 – Lock the patient in the database while assigning:** prevents duplicates, but under heavy demand the locks make doctors wait for each other and repeat the pattern that hurt the legacy system.
  - **Alternative 3 – Let doctors choose a patient from the list:** no extra mechanism, but doctors could pick mild cases or compete for the same patient, and the priority order would not be guaranteed.

- **Decision:** Assign patients with **a single indivisible operation, a double check and a time limit**.
  - Choosing the next patient and assigning it to the doctor happen together as one step. Two doctors can never receive the same patient.
  - If the same request is repeated, the system returns the same patient and never assigns a second one.
  - The consultation record confirms the assignment only if the patient is still waiting. If not (for example, the patient cancelled at that very moment), the assignment is undone and the doctor becomes available again.
  - The patient has a limited time to connect (a few minutes, adjustable). If they do not connect, the consultation is marked as no-show and the doctor becomes available again.
  - Every assignment, undo and no-show is recorded for auditing.

- **Trade-offs:**
  - **Positives (What we gain):**
    - No patient is assigned twice.
    - Doctors do not lose time waiting for patients who never connect.
    - Repeating a request is harmless.
    - The double check is a second safety net if the first step fails.
    - There is a clear record of what happened with each patient.
  - **Negatives (Risks / What we lose):**
    - *Extra messages between modules mean the consultation status updates a moment later.* Mitigation: the doctor sees the assigned patient immediately in the response, and the consultation record remains the final reference.
    - *The time limit is hard to choose:* too short can mark patients as no-show unfairly, too long wastes doctor time. Mitigation: make it adjustable, review real data and let the patient request a consultation again.
    - *An undone assignment may confuse the doctor.* Mitigation: it is rare, it shows a clear message and the doctor receives another patient right away.
    - *Simultaneous requests are hard to test.* Mitigation: run tests with many doctors asking at the same time.

- **Final Justification:** Deciding who attends whom is the heart of this feature, and a mistake here means a patient left without a doctor or a doctor with two patients. This decision is easy to explain (one patient, one doctor), covers the failures that really happen (simultaneous requests, repeated requests, patients who disappear).