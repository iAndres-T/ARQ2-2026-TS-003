create table triage_records (
    consultation_id     uuid primary key,
    patient_id          uuid        not null,
    requested_at        timestamptz not null,
    priority            varchar(10) not null,
    source              varchar(10) not null,
    rule_id             varchar(40),
    rule_set_version    varchar(10) not null,
    triage_version      integer     not null,
    overridden_by       uuid,
    reason              varchar(500),
    -- D7: TRIAGED until PatientAssigned / ConsultationCancelled arrive
    status_projection   varchar(10) not null,
    symptoms            jsonb       not null,
    last_symptoms_version bigint    not null,
    assessed_at         timestamptz not null,
    constraint triage_priority_ck check (priority in ('HIGH','MEDIUM','LOW')),
    constraint triage_source_ck check (source in ('AUTOMATIC','OVERRIDE')),
    constraint triage_status_ck check (status_projection in ('TRIAGED','ASSIGNED','CANCELLED'))
);

create index triage_records_patient_idx on triage_records (patient_id);
