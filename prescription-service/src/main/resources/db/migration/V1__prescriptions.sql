-- Prescriptions (spec §1) and the read-side projection of consultation state used for the POST checks.
create table prescription (
    id              uuid primary key,
    consultation_id uuid         not null,
    patient_id      uuid         not null,
    doctor_id       uuid         not null,
    items           jsonb        not null,
    notes           varchar(1000),
    delivery_status varchar(20)  not null default 'PENDING'
        check (delivery_status in ('PENDING', 'PUBLISHED', 'DEAD_LETTERED')),
    issued_at       timestamptz  not null,
    event_id        uuid         not null unique   -- outbox id of the PrescriptionIssued event
);

create index prescription_consultation_idx on prescription (consultation_id);

-- From ConsultationStarted (IN_PROGRESS) and ConsultationCompleted (COMPLETED).
create table consultation_access (
    consultation_id uuid primary key,
    patient_id      uuid        not null,
    doctor_id       uuid        not null,
    status          varchar(20) not null check (status in ('IN_PROGRESS', 'COMPLETED'))
);
