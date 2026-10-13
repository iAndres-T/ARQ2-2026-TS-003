# ADR-005: Role-Based Access Control (RBAC) for Authentication

- **Status:** Draft
- **Date:** 2026-10-12
- **Deciders:** Team Three

## Context and Problem

MedConnect has two main types of users: patients and medical staff. Each type needs different permissions to access the system.

Patients should be able to request consultations, register symptoms, and view their own information. Medical staff should be able to attend patients, manage consultations, and access authorized medical records.

Since MedConnect handles sensitive medical information, allowing all users to access the same functionalities could create security and privacy risks.

The new architecture needs a simple and secure authentication and authorization mechanism that separates user permissions and prevents unauthorized access.

## Considered Alternatives

- **Alternative 1: No Role Separation.** All authenticated users have access to the same functionalities. This is simple to implement but creates serious security risks because patients could access medical staff functionalities.

- **Alternative 2: Separate Authentication Systems.** Patients and medical staff use different authentication systems. This provides separation but increases development and maintenance complexity.

- **Alternative 3: Role-Based Access Control (RBAC).** Users authenticate through a common identity service, while permissions are assigned according to their roles. This provides centralized authentication and clear access restrictions. (Selected)

## Decision

Adopt **Role-Based Access Control (RBAC)** with two main roles: PATIENT and MEDICAL_STAFF.

1. **Authentication:** Users authenticate through a centralized identity service using secure credentials.

2. **Role Assignment:** Each authenticated user has an assigned role that determines which functionalities they can access.

3. **Authorization:** The API Gateway validates the user's JWT, while each service checks the required role, scope, and resource ownership before allowing access.

4. **Patient Permissions:** Patients can request consultations, register symptoms, and access only their own medical information.

5. **Medical Staff Permissions:** Authorized medical staff can attend patients, manage consultations, and access the medical information required for their responsibilities.

6. **Security:** Unauthorized requests are rejected, preventing users from accessing resources outside their permissions.

## Trade-offs

### Positives (What we gain)

- **Improved Security:** Patients and medical staff have different permissions, reducing unauthorized access to sensitive medical information.
- **Simple Implementation:** RBAC is easy to understand and implement in a university project.
- **Centralized Authentication:** A common identity service simplifies user management.
- **Maintainability:** Permissions can be managed according to user roles without creating separate authentication systems.
- **Scalability:** New roles and permissions can be added as the platform grows.

### Negatives (Risks / What we lose)

- **Role Management:** The system must correctly assign and maintain user roles. Mitigation: manage roles through the identity service.
- **Authorization Complexity:** Each service must validate permissions before returning protected information. Mitigation: implement reusable authorization checks.
- **Token Security:** Compromised authentication tokens could allow unauthorized access. Mitigation: use HTTPS, short-lived JWTs, and secure token validation.

## Final Justification

Role-Based Access Control is the most appropriate solution for MedConnect because it provides a simple, secure, and maintainable way to manage permissions for patients and medical staff.

By combining centralized authentication with role-based authorization, the system protects sensitive medical information and ensures that each user can access only the functionalities and records they are authorized to use.

This decision improves security without introducing unnecessary complexity, making it suitable for the proposed MedConnect architecture.