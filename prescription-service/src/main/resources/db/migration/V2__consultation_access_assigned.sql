-- Spec I2: project from PatientAssigned onward, plus terminal states, so POST /prescriptions can answer 409/403 instead of 404.
alter table consultation_access drop constraint consultation_access_status_check;
alter table consultation_access add constraint consultation_access_status_check
    check (status in ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_SHOW'));
