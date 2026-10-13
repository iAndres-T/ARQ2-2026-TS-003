ADR-005: Role-Based Access Control (RBAC) for Authentication

Status: Draft
Date: 2026-10-12
Deciders: Team Three

Context and Problem

MedConnect has two main types of users: patients and medical staff. Each type needs different permissions to access the system.

Patients should be able to request consultations, register symptoms, and view their own information. Medical staff should be able to attend patients, manage consultations, and access authorized medical records.

Since MedConnect handles sensitive medical information, allowing all users to access the same functionalities could create security and privacy risks.

The new architecture needs a simple and secure authentication and authorization mechanism that separates user permissions and prevents unauthorized access.

Considered Alternatives

Alternative 1: No Role Separation

All authenticated users have access to the same functionalities. This approach is simple but creates security risks because patients could access medical staff functions or information they should not see.

Alternative 2: Individual Permissions for Each User

Permissions are assigned separately to every user. This provides flexibility but becomes difficult to manage and maintain as the number of users increases.

Alternative 3: Role-Based Access Control (RBAC)

Users are assigned a role, such as PATIENT or MEDICAL_STAFF, and permissions are defined according to that role. This approach simplifies permission management and provides better control over access to protected resources.

Decision

Adopt Role-Based Access Control (RBAC) to manage user permissions in MedConnect.

User Authentication: All users must log in before accessing protected features. The system verifies their identity before allowing access.

Role Assignment: Each user is assigned a role: PATIENT or MEDICAL_STAFF.

Patient Permissions: Patients can request consultations, register symptoms, and view their own medical information.

Medical Staff Permissions: Medical staff can attend patients, manage consultations, access authorized medical records, and issue prescriptions.

Backend Authorization: The backend validates the user’s role before allowing access to protected endpoints.

Data Protection: Users can only access medical information they are authorized to view, including verification of access to specific patient records.

Trade-offs

Positives (What we gain):

Improved Security: Users can only access functionalities allowed by their assigned roles.

Medical Data Protection: Helps prevent unauthorized access to sensitive patient information.

Simple Permission Management: Permissions are managed by roles instead of individually for every user.

Easy Maintenance: New users can receive the appropriate permissions by assigning them a role.

Scalability: Additional roles can be introduced in the future if the system requires them.

Negatives (Risks / What we lose):

Role Validation Required: Protected endpoints must verify user permissions. Mitigation: implement centralized authorization checks.

Permission Updates: When new features are added, role permissions may need to be updated. Mitigation: maintain a clear permission configuration.

Limited Flexibility: Some users may require specific permissions beyond their general role. Mitigation: complement role validation with resource-level authorization when necessary.

Final Justification

Role-Based Access Control (RBAC) is the selected solution because it provides a simple, secure, and maintainable way to separate patients and medical staff permissions.

It helps protect sensitive medical information, prevents unauthorized access to system functionalities, and makes user management easier.

For MedConnect, RBAC is an appropriate architectural decision because it satisfies the authentication and authorization requirements without introducing unnecessary complexity.