# ADR-005: Role-Based Access Control (RBAC) for Authentication

- **Status:** Draft
- **Date:** 2026-10-12
- **Deciders:** Team

## Context

MedConnect has two main types of users: patients and medical staff. Each type needs access to different features and information.

Patients should be able to request consultations and view their own information. Medical staff should be able to attend patients and access authorized medical records.

The system needs a simple and secure way to manage these permissions and prevent unauthorized access to medical information.

## Decision

Implement **Role-Based Access Control (RBAC)** to manage authentication and authorization in MedConnect.

- Users must log in before accessing protected features.
- Each user has an assigned role: PATIENT or MEDICAL_STAFF.
- Patients can request consultations and view their own information.
- Medical staff can manage consultations and access authorized medical records.
- The backend validates user roles before allowing access to protected endpoints.
- Users cannot access features that do not belong to their assigned role.

## Alternatives Considered

| Option | Why not (draft) |
|---|---|
| No role separation | Users could access features or information they should not see |
| Individual permissions for every user | More difficult to manage and maintain as the system grows |
| Role-Based Access Control (RBAC) | Selected because it is simple, secure, and easy to implement |

## Consequences

- (+) Improves security by restricting access based on user roles.
- (+) Prevents patients from accessing medical staff functions.
- (+) Protects medical information from unauthorized access.
- (+) Makes permissions easier to manage and maintain.
- (+) Allows new roles to be added in the future.
- (-) Requires role validation in protected endpoints.
- (-) Permissions must be updated when new features are added.