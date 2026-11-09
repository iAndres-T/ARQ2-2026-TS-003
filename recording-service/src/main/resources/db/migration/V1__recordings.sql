-- Metadata only (R-F2-02): the media lives in object storage.
create table recordings (
    id                    uuid primary key,
    consultation_id       uuid         not null,
    provider_recording_id varchar(100) not null,
    status                varchar(20)  not null,
    url                   text,
    duration_seconds      integer,
    recorded_at           timestamptz,
    size_bytes            bigint,
    checksum_sha256       varchar(64),
    attempts              integer      not null default 0,
    failure_reason        text,
    created_at            timestamptz  not null default now(),
    -- S-F2-05: a duplicate provider callback can never create a second row
    constraint recordings_provider_uk unique (provider_recording_id),
    constraint recordings_status_ck check (status in ('PENDING','PROCESSING','AVAILABLE','FAILED'))
);
create index recordings_consultation_idx on recordings (consultation_id, created_at desc);

-- sessionId -> consultationId, filled from ConsultationCompleted
create table session_map (
    session_id      varchar(100) primary key,
    consultation_id uuid not null
);
