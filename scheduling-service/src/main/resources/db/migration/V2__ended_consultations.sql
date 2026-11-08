-- Consultation ids whose end event (completed / no-show / cancelled) was consumed. Makes a late
-- PatientAssigned for an already-ended consultation a no-op (events cross queues out of order).
create table ended_consultations (
    consultation_id uuid primary key,
    ended_at        timestamptz not null default now()
);
