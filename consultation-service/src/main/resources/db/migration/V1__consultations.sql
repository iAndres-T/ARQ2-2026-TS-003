create table consultations (
    id               uuid primary key,
    patient_id       uuid        not null,
    status           varchar(20) not null,
    current_priority varchar(10),
    triage_version   integer     not null default 0,
    symptoms         text        not null,
    doctor_id        uuid,
    requested_at     timestamptz not null,
    assigned_at      timestamptz,
    started_at       timestamptz,
    completed_at     timestamptz,
    version          bigint      not null,
    constraint consultations_status_ck check (status in
        ('REQUESTED','TRIAGED','WAITING','ASSIGNED','IN_PROGRESS','COMPLETED','CANCELLED','NO_SHOW')),
    constraint consultations_priority_ck check (current_priority is null or current_priority in ('HIGH','MEDIUM','LOW'))
);

-- S-F3-05: at most one active consultation per patient, enforced by the database.
create unique index consultations_one_active_per_patient on consultations (patient_id)
    where status in ('REQUESTED','TRIAGED','WAITING','ASSIGNED','IN_PROGRESS');

create index consultations_doctor_idx on consultations (doctor_id) where doctor_id is not null;
