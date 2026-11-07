-- Task 11: video session (provider ACL), patient presence for NO_SHOW, ShedLock for the no-show job.
alter table consultations
    add column session_id       varchar(100),
    add column patient_join_url varchar(1000),
    add column doctor_join_url  varchar(1000),
    add column joined_at        timestamptz;

-- NoShowJob scan (R-F4-05)
create index consultations_unjoined_assigned_idx on consultations (assigned_at)
    where status = 'ASSIGNED' and joined_at is null;

-- care-queue Redis rebuild snapshot (spec §4)
create index consultations_waiting_idx on consultations (requested_at) where status = 'WAITING';

create table shedlock (
    name       varchar(64)  not null primary key,
    lock_until timestamp(3) not null,
    locked_at  timestamp(3) not null,
    locked_by  varchar(255) not null
);
