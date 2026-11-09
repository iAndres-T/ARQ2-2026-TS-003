-- Append-only legal record of every domain event. Enforced here, not by convention.
create table audit_event (
    event_id       uuid        primary key,          -- unique: duplicate deliveries are skipped
    event_type     text        not null,
    aggregate_id   uuid        not null,
    actor_id       uuid,
    occurred_at    timestamptz not null,
    producer       text,
    correlation_id uuid,
    received_at    timestamptz not null default now(),
    payload        jsonb       not null
);

create index audit_event_order_idx     on audit_event (occurred_at, event_id);
create index audit_event_aggregate_idx on audit_event (aggregate_id);
create index audit_event_type_idx      on audit_event (event_type);
create index audit_event_actor_idx     on audit_event (actor_id);

create function audit_event_reject_change() returns trigger language plpgsql as $$
begin
    raise exception 'audit_event is append-only (% rejected)', tg_op using errcode = 'insufficient_privilege';
end $$;

create trigger audit_event_no_update_delete before update or delete on audit_event
    for each row execute function audit_event_reject_change();
create trigger audit_event_no_truncate before truncate on audit_event
    for each statement execute function audit_event_reject_change();
