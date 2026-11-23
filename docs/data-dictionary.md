# CCE Admin Service — Data Dictionary

Consolidated reference for the six tables this service owns plus `api_permissions` (owned by
`gateway-service`, read/written but not created by this service), sourced from
`CCE_Admin_Module_PRD.pdf` §4, §7 and Appendix A/B. **Design stage — no migration has been written
yet.** This document is what `V1__create_admin_service_schema.sql` (milestone B1) should be checked
against, not a record of what exists.

Database/schema: `ccedb`, `public` schema — the same database and schema every other CCE service's
tables already live in (`protocol_definition`, `deviation`, `facility`, …), confirmed directly against
a live dev instance, **not** a separate `admin_service` database (see
[architecture-overview.md §9.1](architecture-overview.md#91-schema-target-and-api_permissionsid-type--resolved)
for why this differs from the PRD's diagram and from `gateway-service`'s own docs). This service's
own Flyway ledger is `flyway_schema_history_admin`, matching the per-service naming convention already
used by `flyway_schema_history_protocol`, `_compliance`, `_scheduler`, etc. `pgcrypto` must be enabled
for UUID generation (B-01) if not already enabled by another service sharing this database.

> **Resolved:** `api_permissions.id` stays `BIGSERIAL`/`Long`, matching `gateway-service`'s existing,
> already-deployed entity — see
> [architecture-overview.md §9.1](architecture-overview.md#91-schema-target-and-api_permissionsid-type--resolved).
> This service's migration does not create that table; it already exists with real rows.

---

## 1. Database Tables

### 1.1 `users` — Console Accounts

A person who can sign in to a CCE console. Mirrored 1:1 with a Keycloak user; joined to the JWT by
`email`. No credentials are stored locally — Keycloak is the only credential store.

> **Changed from the PRD.** `keycloak_id` (Keycloak's permanent, non-editable user id — normally the
> `sub` claim) is removed as the join key; resolution now matches the JWT's `email` claim against
> `users.email` instead. This is a real trade-off, not a neutral simplification — noted explicitly so
> it isn't mistaken for an oversight:
> - `email` **is** editable via `PUT /users/:id` (B-33), and can be changed directly in Keycloak too —
>   either side changing out of sync with the other breaks the join (the request resolves to no local
>   user, or the wrong one) until both are manually realigned.
> - `email` is also not a guaranteed-present JWT claim the way `sub` is — it depends on the client's
>   configured scopes/mappers, unlike `sub`, which every OIDC token carries by spec.
> - Kept for now at the team's direction; revisit if resolution failures/lockouts trace back to this.

| Column | Type | Nullable | Default | Description |
|---|---|---|---|---|
| `id` | `UUID` | No | `gen_random_uuid()` | Primary key |
| `username` | `VARCHAR(100)` | No | — | Display/search identifier |
| `email` | `VARCHAR(255)` | No | — | Unique; also the Keycloak account's email |
| `first_name` | `VARCHAR(100)` | No | — | — |
| `last_name` | `VARCHAR(100)` | No | — | — |
| `status` | `VARCHAR(20)` | No | `'ACTIVE'` | `ACTIVE` \| `INACTIVE` — see §2.1 |
| `facility_scope` | `VARCHAR(255)` | Yes | — | Stored and displayed only this release; not enforced (PRD §2 Out of scope) |
| `last_login_at` | `TIMESTAMPTZ` | Yes | — | **Resolved** (PRD §12 flagged this as unspecified — decided here). Updated as a side effect of `GET /me`: set to the JWT's `auth_time` claim (the actual login moment — constant across token refreshes, unlike `iat`), written only when that login is **newer than the stored value** (`last_login_at IS NULL OR last_login_at < auth_time`), so one session never writes twice and a quick re-login still registers. If the token has no `auth_time`, falls back to `now()`, throttled to one write per 5 minutes (`last_login_at < now() - interval '5 minutes'`). No new endpoint, no Keycloak webhook — see api-reference.md's `GET /me` |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | — |
| `updated_at` | `TIMESTAMPTZ` | No | `now()` | — |

**Constraints:**
- `PK`: `id`
- `UNIQUE`: `email` — violated → `409 EMAIL_IN_USE` (B-31). Also now the sole JWT-resolution join key
  (B-12), so this constraint is load-bearing for both uniqueness *and* authentication correctness —
  two users could never validly share an email even without this, but a mismatch here now means a
  resolution failure, not just a duplicate-account concern.

**Indexes:**
| Index | Columns | Purpose |
|---|---|---|
| `idx_users_status` | `status` | `GET /users?status=` filter (B-30) |
| `idx_users_email` | `email` | Search-by-email, uniqueness check, and every authenticated request's B-12 resolution lookup |

Deactivation (`status = INACTIVE`) is terminal by design — there is no hard delete in this release
(PRD §2 Out of scope), for audit continuity. Re-activating restores `user_roles` rows and the
Keycloak account rather than recreating anything (B-34).

---

### 1.2 `roles` — Named Access Bundles

A named grouping of access, e.g. `FACILITY_ADMIN`. `UPPER_SNAKE_CASE` by convention, enforced
client-side (F-12) and should be enforced server-side too since `name` doubles as the Keycloak realm
role name and `api_permissions.permission_name` (architecture-overview.md §3).

| Column | Type | Nullable | Default | Description |
|---|---|---|---|---|
| `id` | `UUID` | No | `gen_random_uuid()` | Primary key |
| `name` | `VARCHAR(100)` | No | — | Immutable after creation. Also the Keycloak realm role name and `api_permissions.permission_name` |
| `display_name` | `VARCHAR(150)` | No | — | Editable |
| `description` | `TEXT` | Yes | — | Editable |
| `is_system` | `BOOLEAN` | No | `false` | `true` for seeded roles (`SUPER_ADMIN`). Blocks rename/delete — `409 SYSTEM_ROLE_IMMUTABLE` (B-40) |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | — |
| `updated_at` | `TIMESTAMPTZ` | No | `now()` | — |

**Constraints:**
- `PK`: `id`
- `UNIQUE`: `name` — violated → `409 DUPLICATE_ROLE_NAME` (B-40)

**Business rule, not a DB constraint:** delete is blocked with `409 ROLE_IN_USE` when `user_roles`
rows reference the role, or `409 SYSTEM_ROLE_IMMUTABLE` when `is_system = true` — both checked in the
**service layer**, not via a foreign-key violation, so the error code is precise rather than a generic
constraint-violation 500 (B-40, "checked in service layer, not via FK error").

---

### 1.3 `menus` — Console Screen Catalog

One screen of `cce-insights-ui` (or a future console) that can appear as a sidebar link. A flat
catalog — no parent/child nesting.

> **Simplified from the PRD.** The PRD's `type` (`NAV`/`DETAIL`) and `parent_id` columns are dropped.
> They modeled sidebar-vs-drill-down screens as a tree, but that distinction isn't required by this
> module's stated goal (role-based sidebar visibility, PRD §2) or any of its six scenarios — and real
> UAT evidence shows at least one of the PRD's two intended `DETAIL` entries (`Protocol Analytics`)
> isn't an independently-routable screen at all (it's a dropdown-filtered view on the same `/compliance`
> page). Flagged to the PRD owner as a deviation from B-02/B-21/B-41/F-13, not a silent change.

| Column | Type | Nullable | Default | Description |
|---|---|---|---|---|
| `id` | `UUID` | No | `gen_random_uuid()` | Primary key |
| `menu_key` | `VARCHAR(100)` | No | — | Stable identifier, unique. Violated → `409 DUPLICATE_MENU_KEY` (B-44) |
| `label` | `VARCHAR(150)` | No | — | Display text |
| `path` | `VARCHAR(255)` | No | — | Route path in the console |
| `icon` | `VARCHAR(100)` | Yes | — | Icon identifier (console-specific, e.g. a Heroicons name) |
| `sort_order` | `INTEGER` | No | `0` | Ordering among menus |
| `is_active` | `BOOLEAN` | No | `true` | Inactive menus are excluded from `GET /me/menus` (B-21) |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | — |
| `updated_at` | `TIMESTAMPTZ` | No | `now()` | — |

**Constraints:**
- `PK`: `id`
- `UNIQUE`: `menu_key`

**Indexes:**
| Index | Columns | Purpose |
|---|---|---|
| `idx_menus_active` | `is_active` | Filtering inactive rows |

Deleting a menu also removes its `role_menus` rows (B-44) — a hard delete of the join, not of the
role or a soft-deactivation of the mapping.

---

### 1.4 `role_menus` — Role → Menu Assignment

Soft control (architecture-overview.md §2): which menus a role's sidebar shows. Full-replacement
semantics on write — `PUT /roles/:id/menus` sends the complete set, not a diff (B-41).

| Column | Type | Nullable | Default | Description |
|---|---|---|---|---|
| `role_id` | `UUID` | No | — | FK → `roles.id`, `ON DELETE CASCADE` |
| `menu_id` | `UUID` | No | — | FK → `menus.id`, `ON DELETE CASCADE` |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | — |

**Constraints:**
- `PK`: `(role_id, menu_id)` — composite, prevents duplicate assignment

---

### 1.5 `user_roles` — User → Role Assignment

Mirrors a user's Keycloak realm-role assignments. `PUT /users/:id/roles` replaces the full set; the
backend diffs old vs. new to compute which Keycloak realm roles to assign/remove, touching only the
changed ones (B-35).

| Column | Type | Nullable | Default | Description |
|---|---|---|---|---|
| `user_id` | `UUID` | No | — | FK → `users.id`, `ON DELETE CASCADE` |
| `role_id` | `UUID` | No | — | FK → `roles.id`, `ON DELETE RESTRICT` (a role in use blocks deletion with `409 ROLE_IN_USE`, see §1.2) |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | — |

**Constraints:**
- `PK`: `(user_id, role_id)`

Deactivating a user (`PATCH /users/:id/status`) **keeps** `user_roles` rows — re-activation restores
access without re-selecting roles (B-34).

---

### 1.6 `api_permissions` — Gateway Authorization Rules

One `(HTTP method, URI pattern)` rule a role may call. Hard control (architecture-overview.md §2):
`gateway-service` reads this table directly via R2DBC on every proxied request. **This service does
not own this table's shape** — `gateway-service` does, via its own migration
(`DATABASE_PERMISSIONS_MIGRATION.md`). Columns below mirror that existing contract; do not add or
rename columns here without checking against it first (PRD §5 "Fixed constraints you cannot change").

**This table already exists in `ccedb.public` — this service's migration does not create it, only
reads/writes rows into it, same as `gateway-service` does.**

| Column | Type | Nullable | Default | Description |
|---|---|---|---|---|
| `id` | `BIGSERIAL` | No | — | Matches `gateway-service`'s existing `Long id` — the one odd one out among the seven tables, kept intentionally rather than migrated to UUID (architecture-overview.md §9.1) |
| `permission_name` | `VARCHAR(100)` | No | — | `= roles.name` (architecture-overview.md §3) |
| `http_method` | `VARCHAR(10)` | No | — | `GET` \| `POST` \| `PUT` \| `PATCH` \| `DELETE` |
| `uri_pattern` | `VARCHAR(255)` | No | — | Ant-style path pattern gateway-service matches against |
| `description` | `TEXT` | Yes | — | — |
| `resource_category` | `VARCHAR(50)` | Yes | — | Shown as "category" in the Permissions tab (F-14) |
| `created_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | — |
| `updated_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | Auto-updated by trigger in gateway-service's existing migration — replicate the trigger here if this service becomes a co-writer |

**Constraints:**
- `PK`: `id`
- `UNIQUE`: `(permission_name, http_method, uri_pattern)` — matches `unique_permission_method_uri` in
  `gateway-service`'s existing migration. Do not diverge from this name/shape.

**Indexes:**
| Index | Columns | Purpose |
|---|---|---|
| `idx_permission_name` | `permission_name` | Existing in gateway-service's migration — fast lookup by role |
| `idx_resource_category` | `resource_category` | Existing in gateway-service's migration — grouping |

`PUT /roles/:id/permissions` replaces the full set for a role, then calls `gateway-service`'s
`POST /api/permissions/sync-to-keycloak` automatically (B-42, B-43). A sync failure leaves the DB
change in place and returns `502 KEYCLOAK_SYNC_FAILED` — see
[architecture-overview.md §7](architecture-overview.md#7-consistency-model).

---

### 1.7 `admin_audit_log` — Append-Only Mutation Record

Every mutating endpoint writes exactly one row here (B-50). Append-only — no `UPDATE`, no delete, no
`updated_at` column.

| Column | Type | Nullable | Default | Description |
|---|---|---|---|---|
| `id` | `UUID` | No | `gen_random_uuid()` | Primary key |
| `actor_id` | `UUID` | No | — | `users.id` of the caller (resolved from JWT `email` claim, B-12 — see §1.1) |
| `action` | `VARCHAR(50)` | No | — | See §2.2 |
| `entity_type` | `VARCHAR(50)` | No | — | e.g. `USER`, `ROLE`, `MENU` |
| `entity_id` | `UUID` or `VARCHAR` | No | — | id of the affected row. Typed `VARCHAR` if it must also record a `BIGSERIAL` `api_permissions.id` (see §1.6) without a cross-type column; confirm during B1. For `USER_PROVISIONING_FAILED` rows (no local row ever created, B-32), holds the **Keycloak account's own id** instead of a local `users.id` — always present, never `NULL`, since this row only exists once Keycloak succeeded |
| `before` | `JSONB` | Yes | — | State before the mutation; `null` on create |
| `after` | `JSONB` | Yes | — | State after the mutation; `null` on delete/deactivate-only actions |
| `ip_address` | `VARCHAR(45)` | Yes | — | Caller IP (IPv6-safe width) |
| `created_at` | `TIMESTAMPTZ` | No | `now()` | — |

**Constraints:**
- `PK`: `id`

**Indexes:**
| Index | Columns | Purpose |
|---|---|---|
| `idx_admin_audit_log_entity` | `(entity_type, entity_id)` | "What happened to this row" — the only read path this release (manual SQL; no endpoint, see architecture-overview.md §9.5) |
| `idx_admin_audit_log_actor` | `actor_id` | "What did this user do" |
| `idx_admin_audit_log_created` | `created_at` | Time-range queries |

Partial-failure reconciliation also uses this table: when Keycloak's account creation is confirmed but
role assignment then fails, or the final local commit fails after both Keycloak calls already
succeeded (B-32), a dedicated `USER_PROVISIONING_FAILED` row is written (see §2.2) — `entity_id` holds the
Keycloak account's own id rather than a local `users.id` (none exists), and `after` carries
`{keycloakId, username, email, reason}` — so this is the one `action` value whose `entity_id` does not
resolve to a row in this schema, by design.

---

## 2. Enum Values

### 2.1 `users.status`

| Value | Description |
|---|---|
| `ACTIVE` | Normal state. Keycloak account enabled |
| `INACTIVE` | Deactivated — terminal for this release, no hard delete. Keycloak account disabled. `user_roles` retained for re-activation |

> **Simplified from the PRD.** Appendix B also lists a `LOCKED` value (as a `403 FORBIDDEN` cause), but
> no requirement describes what sets or clears it — `PATCH /users/:id/status` (B-34) only documents
> `ACTIVE`/`INACTIVE`. As written, `LOCKED` would be a dead end: nothing could ever recover a user from
> it. Narrowed to two values for this release; account lockout (after N failed logins, presumably
> `LOCKED`'s intent) is deferred as a feature needing its own spec — trigger condition, who/what clears
> it, Keycloak-driven vs. app-driven. Flagged to the PRD owner as a heads-up, not a blocking question.

### 2.2 `admin_audit_log.action`

Values named explicitly across the requirements (B-31 through B-44); treat as the minimum set, not
exhaustive — any future mutating endpoint needs its own value here.

| Value | Written by |
|---|---|
| `USER_CREATED` | `POST /users` (B-31) — `after` includes `"inviteEmailSent": false` if the automatic invite email failed to send (non-fatal; see api-reference.md) |
| `USER_PROVISIONING_FAILED` | `POST /users`, when Keycloak's account creation is confirmed but role assignment fails, or the final local commit fails after both Keycloak calls succeed (B-32) — the one action whose `entity_id` is a Keycloak account id, not a local row (§1.7). Not written when the account-creation call itself fails — that case rolls back cleanly with nothing to reconcile, or carries an accepted residual risk if the failure was ambiguous (architecture-overview.md §7) |
| `USER_UPDATED` | `PUT /users/:id` |
| `USER_DEACTIVATED` | `PATCH /users/:id/status` → `INACTIVE` (B-34) |
| `USER_REACTIVATED` | `PATCH /users/:id/status` → `ACTIVE` (B-34) |
| `USER_ROLES_UPDATED` | `PUT /users/:id/roles` (B-35) |
| `USER_PASSWORD_RESET` | `POST /users/:id/reset-password` (B-36) |
| `ROLE_CREATED` | `POST /roles` |
| `ROLE_UPDATED` | `PUT /roles/:id` |
| `ROLE_DELETED` | `DELETE /roles/:id` |
| `ROLE_MENUS_UPDATED` | `PUT /roles/:id/menus` (B-41) |
| `ROLE_PERMISSIONS_UPDATED` | `PUT /roles/:id/permissions` (B-42) |
| `MENU_CREATED` / `MENU_UPDATED` / `MENU_DELETED` | `/menus` CRUD (B-44) |

---

## 3. Migrations (planned)

No migration exists yet. Suggested layout per milestone B1 (PRD §10):

| Version | File | Covers |
|---|---|---|
| V1 | `V1__create_admin_service_schema.sql` | The **six** tables this service owns (`users`, `roles`, `menus`, `role_menus`, `user_roles`, `admin_audit_log`), straight from §1.1–1.5 and §1.7 of this document, created in `ccedb`'s `public` schema with ledger `flyway_schema_history_admin`. Does **not** touch `api_permissions` — that table already exists (§1.6). Have a reviewer diff the columns against this file before merging (PRD milestone note) |
| V2 | `V2__seed.sql` | The **7** real `cce-insights-ui` sidebar menus (§1.3 — Dashboard, Facilities, Compliance, Deviations, Patients, Events, Ingestion, matching `Sidebar.tsx`; the PRD's 12-menu NAV/DETAIL model is not used), the `SUPER_ADMIN` system role (§1.2) linked to all 7, and 7 bootstrap `api_permissions` rows (`permission_name = 'SUPER_ADMIN'`, category `Admin`: `GET /admin/v1/me`, `GET /admin/v1/me/menus`, and `GET/POST/PUT/PATCH/DELETE /admin/v1/**`) — inserted as rows into the existing table, not a schema change. Idempotent (`ON CONFLICT` / `WHERE NOT EXISTS`). Seeds **no named admin account** — the first `SUPER_ADMIN` is provisioned per environment by `scripts/bootstrap-admin.sh` (see [architecture-overview.md §9.4](architecture-overview.md#94-bootstrap-sequencing--no-concrete-owner-yet)) |

`pgcrypto` must be enabled in V1 before any `gen_random_uuid()` default is used — check first whether
another service sharing `ccedb` has already enabled it.

**Confirmed when actually booted against the real local `ccedb`:** Flyway refuses to run against
`public` by default, since the schema already has every other service's tables in it and Flyway
can't tell those apart from an untracked version of its own history. `cce-protocol-service` and
`cce-matcher-service` already hit this and fixed it the same way — `baseline-on-migrate: true` with
`baseline-version: 0` (prod profiles flip it back to `false` once their own ledger is real). This
service's `application.yml` now does the same.
