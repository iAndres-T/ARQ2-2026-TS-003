-- Review fix: the no-show window starts when consultation-service commits ASSIGNED, not at care-queue's claim
-- time (assigned_at), so a PatientAssigned delayed by provider retries does not shorten the patient's window.
alter table consultations add column assigned_committed_at timestamptz;
update consultations set assigned_committed_at = assigned_at where assigned_at is not null;

drop index consultations_unjoined_assigned_idx;
create index consultations_unjoined_assigned_idx on consultations (assigned_committed_at)
    where status = 'ASSIGNED' and joined_at is null;
