# ADR-005: Role-Based Access Control (RBAC) for Authentication and Authorization

- **Status:** Draft
- **Date:** 2026-10-12
- **Deciders:** Team Three

## Context and Problem

MedConnect has two main types of users: patients and medical staff. Each group requires different permissions to interact with the platform.

Patients can request consultations, register symptoms, and access their own medical information. Medical staff can attend patients, manage consultations, and access medical records when authorized.

Because MedConnect handles sensitive medical information, authentication alone is insufficient. The system must enforce authorization rules to prevent access to unauthorized functionalities and resources.

MedConnect follows a microservices architecture, so responsibilities must be clearly divided between the API Gateway, Identity Service, and individual services.

## Considered Alternatives

- **Alternative 1: No Role Separation.** All authenticated users have the same permissions. This is simple but exposes sensitive medical information and staff-only functionalities.

- **Alternative 2: Separate Authentication Systems.** Patients and medical staff use independent authentication systems. This separates users but duplicates logic and increases maintenance complexity.

- **Alternative 3: Role-Based Access Control (RBAC).** Users authenticate through a common Identity Service, and permissions depend on their roles. This provides centralized authentication and clear access restrictions. **(Selected)**

## Decision

Adopt **Role-Based Access Control (RBAC)** with two initial roles: `PATIENT` and `MEDICAL_STAFF`.

### Authentication

- Users authenticate through a centralized Identity Service.
- The Identity Service issues JWTs containing the user identifier, role, and relevant authorization claims.
- JWTs use asymmetric signing with **RS256**.
- The API Gateway and services verify signatures using trusted public keys without synchronous calls to Identity.
- Access tokens are short-lived, and securely stored, revocable refresh tokens support session renewal.

### Authorization Responsibilities

- **API Gateway (coarse-grained):** Validates JWT signature, issuer, audience, expiration, and role-based route access.
- **Individual services (fine-grained):** Validate identity and applicable claims, required permissions, resource ownership, and authorized relationships.
- Unauthorized requests are rejected by default.

### Role Permissions

- **PATIENT:** Request consultations, register their own symptoms, and view only their own medical records.
- **MEDICAL_STAFF:** Attend assigned patients, manage authorized consultations, and access or update medical records when permitted.
- **PriorityOverridden:** Only authorized `MEDICAL_STAFF` users may override consultation priority under the applicable business rules.

### Medical Record Access

- Patients can access only their own medical information.
- Medical staff can access records of patients currently assigned to them or previously attended, according to the clinical access policy.
- The Medical Records Service maintains a local projection of authorized patient-staff relationships.
- Assignment and consultation services publish events such as `PatientAssigned`, `PatientUnassigned`, and, when relevant, `ConsultationClosed`.
- Medical Records consumes these events and updates its authorization projection without synchronous cross-service requests.
- Missing or inconsistent authorization information results in denied access.
- Time-critical permission revocations require additional safeguards because projections are eventually consistent.

### Authentication and Authorization Flow

1. A user authenticates through the Identity Service.
2. Identity validates credentials and issues a signed JWT.
3. The client sends the JWT with API requests.
4. The API Gateway validates the token and checks route-level permissions.
5. The request reaches the corresponding service.
6. The service verifies operation permissions and resource-level authorization.
7. Requests failing any authorization check are rejected.

### Service-to-Service Authentication

- Internal services authenticate using dedicated service credentials.
- Events published through the message broker do not contain end-user JWTs.
- Each service has limited permissions to publish or consume specific events.

### Security

- **Least Privilege:** Users access only authorized resources.
- **JWT Validation:** RS256 signature and claim verification.
- **Token Protection:** Short-lived access tokens and securely managed refresh tokens.
- **Encrypted Communication:** HTTPS protects client communication.
- **Service Authentication:** Dedicated identities protect internal messaging.
- **Audit Logging:** Sensitive operations are logged without exposing tokens or unnecessary clinical data.
- **Default Denial:** Invalid or unauthorized requests are rejected.

## Trade-offs

### Positives (What We Gain)

- **Improved Security:** Clear separation between patient and medical staff permissions.
- **Clear Responsibilities:** Gateway authorization by route and service authorization by resource.
- **Centralized Authentication:** Consistent management of user identities and roles.
- **Reduced Coupling:** Local JWT validation and event-driven authorization projections.
- **Maintainability:** Reusable access checks and consistent role-based rules.
- **Scalability:** Additional roles and permissions can be introduced as needed.

### Negatives (Risks and Mitigations)

- **Role Management:** Incorrect assignments could expose restricted resources. **Mitigation:** Centrally manage roles and restrict role changes.
- **Distributed Authorization:** Each service must enforce its own permissions. **Mitigation:** Reusable checks and automated security tests.
- **Token Compromise:** Stolen access tokens remain usable until expiration. **Mitigation:** HTTPS, short lifetimes, and secure refresh-token management.
- **Eventual Consistency:** Local projections may temporarily be outdated. **Mitigation:** Reliable event delivery, idempotent processing, and safeguards for urgent access revocation.
- **Additional Infrastructure:** Service identities and projections require maintenance. **Mitigation:** Standardize security mechanisms and minimize stored authorization data.

## Final Justification

**Role-Based Access Control (RBAC)** provides MedConnect with a secure and maintainable mechanism to separate patient and medical staff permissions.

Combining centralized authentication, coarse-grained authorization at the API Gateway, and fine-grained authorization within services ensures access is restricted by both user role and authorized resource relationships.

RS256-signed JWTs reduce synchronous Identity Service dependencies, while event-driven local projections support independent medical-record authorization decisions.

This approach protects sensitive clinical information, reduces unnecessary coupling, and provides a scalable foundation without introducing separate authentication systems.