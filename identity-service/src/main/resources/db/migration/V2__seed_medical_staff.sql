-- Test credentials only: the password for all three accounts is documented in the task report (BCrypt below).
INSERT INTO users (id, email, password_hash, full_name, role, doctor_id, scopes) VALUES
 ('00000000-0000-4000-8000-000000000001', 'doctor1@medconnect.test', '$2a$10$CHMIEWad/aUbXuuDYOy2EeSO.H5lsAIj4MVlYlxHUjZJiWSmo9SYi', 'Doctor One',   'MEDICAL_STAFF', 'd0c70000-0000-4000-8000-000000000001', 'scheduling:manage,recordings:read,audit:read'),
 ('00000000-0000-4000-8000-000000000002', 'doctor2@medconnect.test', '$2a$10$CHMIEWad/aUbXuuDYOy2EeSO.H5lsAIj4MVlYlxHUjZJiWSmo9SYi', 'Doctor Two',   'MEDICAL_STAFF', 'd0c70000-0000-4000-8000-000000000002', ''),
 ('00000000-0000-4000-8000-000000000003', 'doctor3@medconnect.test', '$2a$10$CHMIEWad/aUbXuuDYOy2EeSO.H5lsAIj4MVlYlxHUjZJiWSmo9SYi', 'Doctor Three', 'MEDICAL_STAFF', 'd0c70000-0000-4000-8000-000000000003', 'scheduling:manage');
