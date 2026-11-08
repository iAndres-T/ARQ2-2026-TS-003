create table shifts (
    shift_id   uuid primary key,
    doctor_id  uuid        not null,
    starts_at  timestamptz not null,
    ends_at    timestamptz not null,
    constraint shifts_range_ck check (ends_at > starts_at)
);
create index shifts_doctor_range_idx on shifts (doctor_id, starts_at, ends_at);

-- One row per doctor; created OFFLINE with the first shift and used as the per-doctor lock
-- (select ... for update) that serialises shift creation and every status change.
create table doctor_availability (
    doctor_id              uuid primary key,
    status                 varchar(10) not null,
    since                  timestamptz not null,
    shift_id               uuid,
    current_consultation_id uuid,
    version                bigint      not null default 0,
    constraint availability_status_ck check (status in ('FREE','BUSY','OFFLINE'))
);

create table shedlock (
    name       varchar(64)  not null primary key,
    lock_until timestamp(3) not null,
    locked_at  timestamp(3) not null,
    locked_by  varchar(255) not null
);
