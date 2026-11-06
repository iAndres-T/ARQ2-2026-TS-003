-- Idempotency-Key replay store (spec §2, 24 h). status null = request still running.
create table idempotency_keys (
    key        varchar(255) not null,
    principal  uuid         not null,
    status     integer,
    body       jsonb,
    created_at timestamptz  not null default now(),
    primary key (key, principal)
);

create index idempotency_keys_created_at_idx on idempotency_keys (created_at);
