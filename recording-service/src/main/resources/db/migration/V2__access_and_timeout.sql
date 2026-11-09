-- Resource-level authorization (ADR-003/005): who handled the consultation. Old rows stay NULL and deny access.
alter table session_map add column doctor_id uuid;
alter table session_map add column patient_id uuid;
alter table session_map add column created_at timestamptz not null default now();
create index session_map_consultation_idx on session_map (consultation_id);

-- ShedLock (processing-timeout job)
create table shedlock (
    name       varchar(64)  not null primary key,
    lock_until timestamp(3) not null,
    locked_at  timestamp(3) not null,
    locked_by  varchar(255) not null
);
