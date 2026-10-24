## ODP YugabyteDB Portal – Access Management Requirements

The ODP Portal needs an authorization mechanism that controls user access to YugabyteDB metadata and database-related operations based on the user's role and entitlement.

### 1. Global access entitlements

- ODP YugabyteDB Admin: Grants YugabyteDB and other authorized internal ODP teams administrative access across all databases.
- ODP YugabyteDB View: Grants YugabyteDB and other authorized internal ODP teams read-only access across all databases.

### 2. Database-specific entitlements

- Each database or project can have its own entitlement to manage access for the respective team.
- The entitlement must have a designated owner responsible for access management.
- Entitlement owners or administrators can add and remove users.
- Users can request access to a specific database entitlement.
- The responsible team reviews and approves or rejects access requests.

### 3. User authentication and authorization

- The portal identifies users through their login user ID.
- The system determines access based on the user's identity and assigned permissions.
- An alternative is to authorize users directly through their login identity without requiring explicit entitlements, simplifying access management.
- The solution should balance ease of access with the ability to manage and govern database permissions.

### 4. Audit and accountability

- Track entitlement ownership, membership changes, access requests, approvals, and revocations.
- Maintain an audit trail identifying the users who perform access-related actions.

### 5. Key decision

Determine whether to implement:

- Entitlement-based access: Provides controlled access requests, team-managed approvals, and delegated ownership.
- Login-based authorization: Simplifies access by using the user's identity and predefined authorization rules.

Objective: Establish a secure, manageable, and scalable access model that supports both platform-wide ODP access and database-specific access managed by the respective teams.