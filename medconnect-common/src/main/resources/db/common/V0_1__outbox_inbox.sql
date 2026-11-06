-- Transactional outbox (ADR-002) and consumer inbox for eventId deduplication.
create table outbox (
    id           uuid primary key,
    exchange     varchar(255) not null,
    routing_key  varchar(255) not null,
    envelope     jsonb        not null,
    traceparent  varchar(55),
    created_at   timestamptz  not null default now(),
    published_at timestamptz,
    -- unroutable events (no queue bound yet) are retried with backoff instead of blocking the relay
    attempts        integer not null default 0,
    next_attempt_at timestamptz,
    last_error      text
);

create index outbox_unpublished_idx on outbox (created_at) where published_at is null;

create table processed_events (
    event_id     uuid         not null,
    consumer     varchar(255) not null,
    processed_at timestamptz  not null default now(),
    primary key (event_id, consumer)
);

create index processed_events_processed_at_idx on processed_events (processed_at);
