# API Reference — Admin Service

Base path: `/admin/v1`. Content type: `application/json` on request and response.

**Design stage — no controller exists yet.** This is the hand-written contract the service should be
built against, derived from `CCE_Admin_Module_PRD.pdf` §7, §8 and Appendices A/B. It exists alongside
the generated OpenAPI/Swagger UI (B-06) rather than instead of it: Swagger gives integrating engineers
a live, explorable spec; this document gives the "why" behind each shape and error code, matching how
every other service in this platform documents itself (`api-reference.md` as the authoritative
narrative contract — see the Rwanda handover's documentation convention note).

All endpoints require a Bearer JWT except `GET /health`. A caller reaching this service has already
passed `gateway-service`'s authorization check for the route; this service re-validates the JWT
independently (defense in depth — [architecture-overview.md §5](architecture-overview.md#5-request-lifecycle-authenticated-endpoints)).

## Response envelope

Success:
```json
{ "data": { }, "pagination": { "page": 0, "pageSize": 20, "total": 0 } }
```
`pagination` is present only on list endpoints (B-04 — default page size 20, max 100).

An optional `warnings` array may accompany a success response when the primary operation succeeded
but a non-critical side effect didn't (e.g. `POST /users`' invitation email failing to send — see
below). `warnings` never downgrades the HTTP status; it's informational only.

Error:
```json
{ "error": { "code": "VALIDATION_ERROR", "message": "..." } }
```

## Error codes

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Body or query failed validation |
| 401 | `UNAUTHENTICATED` | Missing, invalid or expired token |
| 403 | `FORBIDDEN` | Role lacks permission, or local account not `ACTIVE` |
| 404 | `USER_NOT_FOUND` / `ROLE_NOT_FOUND` / `MENU_NOT_FOUND` | Unknown id |
| 409 | `DUPLICATE_ROLE_NAME` / `DUPLICATE_MENU_KEY` / `EMAIL_IN_USE` | Unique constraint conflict |
| 409 | `ROLE_IN_USE` | Delete of a role still assigned to users |
| 409 | `SYSTEM_ROLE_IMMUTABLE` | Modify or delete of an `is_system` role |
| 502 | `KEYCLOAK_SYNC_FAILED` | Keycloak or gateway sync call failed; local state is partially applied — see [architecture-overview.md §7](architecture-overview.md#7-consistency-model) |

Every code above maps 1:1 to `@RestControllerAdvice` per milestone B1 — build this mapping before any
other endpoint, since every later milestone depends on it (PRD milestone note).

---

## Current user

### `GET /me`

Caller's own profile, resolved from the JWT.

```json
{ "data": { "id": "...", "username": "...", "roles": ["FACILITY_ADMIN"], "facilityScope": "..." } }
```

No pagination. `roles` is role **names**, not full role objects, taken from the user's local `user_roles` assignment — not from the raw JWT, whose realm roles also include Keycloak-internal ones (`default-roles-cce`, `offline_access`, `uma_authorization`). A valid token whose email has no local `ACTIVE` user is rejected with `403` before this endpoint runs.

**Side effect: updates `last_login_at`.** Since every console calls this once per session (header
load, right after login), it's reused as the trigger point for tracking logins rather than adding a
new endpoint or a Keycloak webhook. Sets `last_login_at` to the JWT's **`auth_time`** claim — the
actual login moment, which stays constant across token refreshes (unlike `iat`, which changes every
refresh) — but only when it is **newer than the stored value**, so repeated calls within one session
don't write at all, and a quick re-login still registers. If `auth_time` is absent, it falls back to
`now()`, throttled to one write per 5 minutes. See
[data-dictionary.md §1.1](data-dictionary.md#11-users--console-accounts).

**`auth_time` presence isn't guaranteed by default — confirmed present for `cce-admin-ui`.** (Verified Milestone 2 against local Keycloak 26.5.4: a PKCE login through the `cce-admin-ui` client yields a token with `email`, `preferred_username` and `auth_time`. Still unchecked for `cce-insights-ui`'s client. Note `admin-cli` direct-grant tokens are lightweight and carry none of these, so they can't be used to test this API.) Same caveat category as `email` —
Keycloak only includes `auth_time` in a token if the client requests it (via `max_age` on the auth
request, or a client scope/mapper configured to always include it). Confirm `cce-admin-ui`'s and
`cce-insights-ui`'s Keycloak client configuration actually includes it before relying on this;
fall back to `now()` at write time if it's ever absent, rather than skipping the update entirely.

### `GET /me/menus`

A flat list of menus for every role the caller holds — a **union** across roles, not per-role. Only
`is_active` menus are included, ordered by `sort_order` (B-21).

```json
{ "data": [
  { "id": "...", "label": "Dashboard", "path": "/", "icon": "ChartBarIcon" },
  { "id": "...", "label": "Compliance", "path": "/compliance", "icon": "ClipboardDocumentCheckIcon" }
] }
```

> **Simplified from the PRD.** No `NAV`/`DETAIL` distinction, no nesting — see
> [data-dictionary.md §1.3](data-dictionary.md#13-menus--console-screen-catalog) for why.

This is what a console's sidebar should render from — `cce-admin-ui`'s own shell does **not** use
this endpoint (F-03: its sidebar is static; see the note in that section). Today only `cce-admin-ui`
exists to call it; wiring `cce-insights-ui` to use this instead of its hardcoded sidebar is explicitly
a separate change in that repo (PRD §12).

A role with no permission row for `GET /admin/v1/me/menus` itself cannot load a sidebar in any
console — see the bootstrap note in
[architecture-overview.md §9.4](architecture-overview.md#94-bootstrap-sequencing--no-concrete-owner-yet).

---

## Users

`/admin/v1/users`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | List, filtered, paginated |
| `POST` | `/` | Create — provisions Keycloak + local row in one call |
| `GET` | `/:id` | Fetch |
| `PUT` | `/:id` | Update profile fields |
| `PATCH` | `/:id/status` | Activate / deactivate |
| `PUT` | `/:id/roles` | Replace role assignment |
| `POST` | `/:id/reset-password` | Trigger a Keycloak password reset |

### `GET /admin/v1/users`

Query params (all optional, combined with AND): `status` (`ACTIVE` or `INACTIVE`, any case — `LOCKED` was dropped, see data-dictionary.md §2.1),
`roleId`, `facilityScope` (exact, case-insensitive), `q` (case-insensitive substring of username, email,
first or last name; `%` and `_` are literal), `page` (0-based, default 0), `pageSize` (1–100, default 20)
(B-30). A bad `status`, `roleId`, `page` or `pageSize` is `400 VALIDATION_ERROR`.

Newest first. Each item has the same shape as the `POST` response (including `roles` as role names and
`lastLoginAt`, which is `null` until the first login — an `ACTIVE` user with `null` is the "needs
attention" case in the UI). The envelope carries `pagination: { page, pageSize, total }`.

### `POST /admin/v1/users`

**Local-first, with rollback.** Inserts the `users` row, `user_roles` rows, and an `admin_audit_log`
(`USER_CREATED`) row inside one open transaction, *then* creates the Keycloak account and assigns
realm roles, committing only once both succeed — then triggers a combined `VERIFY_EMAIL` +
`UPDATE_PASSWORD` Keycloak action email as the very last step. See
[flow-diagrams.md](flow-diagrams.md#flow-2--user-creation) for the full sequencing, including the
accepted residual risk of this ordering and why the email still comes last.

```json
{ "username": "...", "email": "...", "firstName": "...", "lastName": "...",
  "facilityScope": "...", "roleIds": ["..."] }
```

`username` 3–100 chars of letters, digits and `. _ @ + -`; `email` a valid address; both are
**lower-cased by the server** before anything else (Keycloak does the same, and the token's `email`
claim is matched against the stored value). `facilityScope` is optional free text. `roleIds` may be
empty — a user with no roles is allowed; duplicates in it are collapsed.

`201` with the created user:

```json
{ "data": { "id": "...", "username": "mira.live", "email": "mira@example.org", "firstName": "Mira",
  "lastName": "Live", "status": "ACTIVE", "roles": ["FACILITY_ADMIN"], "facilityScope": "Kigali",
  "lastLoginAt": null, "createdAt": "..." } }
```

No separate "send invite" step is exposed — creation handles it automatically.

**Checks that run first — no transaction, no write.** In this order: body validation → every `roleId`
exists locally → email not already used (case-insensitively) → username not already used → **every
role already exists as a Keycloak realm role** (a read-only Keycloak call; a role created in the
Roles screen only reaches Keycloak once its permissions are synced, Milestone 4). A rejection at any
of these touches nothing; a duplicate email makes **zero** Keycloak calls.

**Invitation email.** One action-token email covering both `VERIFY_EMAIL` and `UPDATE_PASSWORD` in
sequence, link valid for **72 hours** (`KEYCLOAK_INVITE_LIFESPAN_SECONDS`, matching this platform's
existing `onboard-insights-user.sh` precedent). The link sends the user on to `cce-admin-ui`
(`KEYCLOAK_INVITE_CLIENT_ID` / `KEYCLOAK_INVITE_REDIRECT_URI`). If the send fails, it does **not** fail
the request — the user/roles/local row are already fully valid at that point, so the response is still
`201`, with a `warnings` entry instead:
```json
{ "data": { "...": "..." }, "warnings": ["Invitation email could not be sent — use Reset Password to resend"] }
```
The same failure is noted in that `USER_CREATED` audit row's `after` JSON (`"inviteEmailSent": false`)
rather than written as a separate audit action. No automatic retry — `POST /users/:id/reset-password`
(Milestone 6) is the recovery path, same as for an expired/lost link (see that endpoint below). A realm
with no SMTP configured fails every send this way — locally run `scripts/setup-local-mail.sh`.

**Partial failure (B-32) — three different shapes, handled differently.**
- **Keycloak definitively refuses the account** because that username/email already exists *there*
  (e.g. a user created directly in Keycloak, unknown to this console) → the local transaction rolls
  back, nothing persists, and the response is `409 EMAIL_IN_USE` / `USERNAME_IN_USE` — a clean "no",
  not a `502`.
- **A Keycloak call itself fails** (account creation or role assignment) → the whole local
  transaction rolls back, nothing persists locally, `502 KEYCLOAK_SYNC_FAILED`. If the failure was a
  definitive rejection, this is fully clean (Keycloak created nothing either). If it was an
  **ambiguous** failure (a timeout), Keycloak may have actually succeeded despite no confirmation
  reaching us — an accepted residual risk of this ordering, not something this release detects or
  reconciles (see [architecture-overview.md §7](architecture-overview.md#7-consistency-model)).
- **Keycloak's account creation is confirmed, but role assignment fails, or the final commit fails
  after both Keycloak calls already succeeded** → Keycloak's success is *not* ambiguous here, so this
  case is always traceable: a reconciliation row is written in its own, immediately-committed
  transaction — `action=USER_PROVISIONING_FAILED`, `entity_type='USER'`,
  `entity_id=<the Keycloak account's own id>` (not a local `users.id` — none survives), `after=
  {keycloakId, username, email, reason}` — *before* the main transaction rolls back, then
  `502 KEYCLOAK_SYNC_FAILED`. See [data-dictionary.md §2.2](data-dictionary.md#22-admin_audit_logaction).

No email is sent in either `502` case, since the email step only runs after a successful commit —
nobody gets a link to an account that isn't fully, durably usable yet. There is no automatic cleanup
of an orphaned Keycloak account in this release — manual reconciliation only.

| Status | Cause |
|---|---|
| `400` | `VALIDATION_ERROR` — missing/invalid fields, malformed JSON, or an unknown `roleId` |
| `409` | `EMAIL_IN_USE` — locally (any letter case) or already in Keycloak |
| `409` | `USERNAME_IN_USE` — locally (any letter case) or already in Keycloak |
| `409` | `ROLE_NOT_SYNCED` — a chosen role is not in Keycloak yet; open its Permissions tab and Save or Re-sync first |
| `502` | `KEYCLOAK_SYNC_FAILED` — nothing was saved (or, for the confirmed-account shapes above, only the reconciliation audit row) |

### `GET /admin/v1/users/:id`, `PUT /admin/v1/users/:id`

`PUT` updates `firstName`, `lastName`, `email`, `facilityScope` only — not `status` or roles, which
have their own endpoints below. `404 USER_NOT_FOUND` if the id is unknown.

### `PATCH /admin/v1/users/:id/status`

```json
{ "status": "INACTIVE" }
```

Deactivating disables the Keycloak account and sets local `status = INACTIVE`; `user_roles` rows are
**kept**, not deleted. Re-activating (`status = ACTIVE`) re-enables the Keycloak account and restores
access without re-selecting roles (B-34). Audits `USER_DEACTIVATED` / `USER_REACTIVATED`.

### `PUT /admin/v1/users/:id/roles`

```json
{ "roleIds": ["...", "..."] }
```

**Full-replacement semantics** — the payload is the complete desired set, not a delta. The service
diffs it against the current `user_roles` rows and assigns/removes only the realm roles that actually
changed in Keycloak (B-35) — see
[flow-diagrams.md](flow-diagrams.md#flow-3--role-assignment-diff). Audits `USER_ROLES_UPDATED` with
the before/after role id sets.

### `POST /admin/v1/users/:id/reset-password`

No body. Triggers the same combined `VERIFY_EMAIL` + `UPDATE_PASSWORD` action email `POST /users`
sends at creation (72-hour link); no password ever passes through this service (B-36). `200` with no
notable body. Audits `USER_PASSWORD_RESET`.

Serves two distinct purposes with the same call: **resend** (the creation email expired, was lost, or
failed to send — see the warning case above) and **genuine reset** (a security concern for an
already-onboarded, active user). Each call generates a fresh action token and invalidates any
previously issued, unused one.

`cce-admin-ui`'s Users list (F-10) surfaces this more prominently — a "needs attention" indicator —
for any user with `status = ACTIVE` and `last_login_at IS NULL` (never completed onboarding; see
[data-dictionary.md §1.1](data-dictionary.md#11-users--console-accounts)), distinct from its plain,
always-available presence on every other row.

---

## Roles

`/admin/v1/roles`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | List |
| `POST` | `/` | Create |
| `GET` | `/:id` | Fetch |
| `PUT` | `/:id` | Update `displayName` / `description` only — `name` is immutable |
| `DELETE` | `/:id` | Delete — guarded |
| `GET` / `PUT` | `/:id/menus` | Read / replace the role's menu set |
| `GET` / `PUT` | `/:id/permissions` | Read / replace the role's permission set |
| `POST` | `/:id/sync` | Manually re-trigger the gateway sync |

### `POST /admin/v1/roles`

```json
{ "name": "FACILITY_ADMIN", "displayName": "Facility Administrator", "description": "..." }
```

`name` must be `UPPER_SNAKE_CASE` and is **permanent** — there is no rename endpoint. Precisely:
uppercase letters and digits, words separated by a **single** underscore, starting with a letter,
at most 100 characters (`FACILITY_ADMIN`, `L2_SUPPORT` pass; `facility_admin`, `_ADMIN`, `ADMIN_`,
`FAC__ADMIN`, `1ADMIN` don't). `displayName` is required (max 150); `description` is optional. `201`
on success, with the created role:

```json
{ "data": { "id": "...", "name": "FACILITY_ADMIN", "displayName": "Facility Administrator",
  "description": "...", "isSystem": false, "createdAt": "...", "updatedAt": "..." } }
```

`GET /roles` returns the same shape as an array, ordered by `name` (not paginated — the set is
small). A malformed JSON body or a non-UUID `:id` in any path is also `400 VALIDATION_ERROR`, never a
`500`.

| Status | Cause |
|---|---|
| `400` | `VALIDATION_ERROR` — `name` not `UPPER_SNAKE_CASE` |
| `409` | `DUPLICATE_ROLE_NAME` |

### `PUT /admin/v1/roles/:id`

Only `displayName` and `description` are accepted; a `name` field in the body, if present, is
ignored rather than rejected — keep the client from needing special-case handling for an otherwise
uniform "replace this resource" shape. **Decided (Milestone 3):** ignore, not `400`. `displayName` is
required; a blank `description` is stored as `null`. A system role (`is_system`) cannot be modified at
all → `409 SYSTEM_ROLE_IMMUTABLE`.

### `DELETE /admin/v1/roles/:id`

`204` with no body on success; the role's `role_menus` rows are removed with it. Audits `ROLE_DELETED`.
The system-role check runs first, then the in-use check.

| Status | Cause |
|---|---|
| `409` | `ROLE_IN_USE` — one or more users still hold this role (checked in the service layer against `user_roles`, not via a foreign-key violation) |
| `409` | `SYSTEM_ROLE_IMMUTABLE` — `is_system = true` (e.g. `SUPER_ADMIN`) |

### `GET /admin/v1/roles/:id/menus`, `PUT /admin/v1/roles/:id/menus`

`GET` returns the role's current menu ids (cross-reference against `GET /menus` for the full catalog —
`cce-admin-ui`'s Role Detail → Menus tab does exactly this, F-13). `PUT` replaces the full set.

```json
{ "menuIds": ["...", "..."] }
```

Full-replacement semantics — the payload is always the complete desired set; an empty list clears the
role's menus. Duplicate ids in the payload are collapsed. Saving the same set twice succeeds and
still writes an audit row. If **any** id is not a known menu, the whole request is rejected with
`404 MENU_NOT_FOUND` and the existing set is left untouched. Allowed on system roles too (editing
`SUPER_ADMIN`'s menus cannot lock anyone out of this console, whose own sidebar is static). Audits
`ROLE_MENUS_UPDATED` (before/after menu ids).

```json
{ "data": { "menuIds": [...] } }
```

### `GET /admin/v1/roles/:id/permissions`, `PUT /admin/v1/roles/:id/permissions`

`GET` returns the role's rows from `api_permissions` (`permission_name` = role name), ordered by pattern
then method:

```json
{ "data": { "permissions": [
  { "id": 43, "httpMethod": "GET", "uriPattern": "/v1/insights/compliance/**",
    "description": "Read compliance insights", "category": "COMPLIANCE" }
] } }
```

`PUT` replaces the **complete** set (the body is never a diff) and then syncs:

```json
{ "permissions": [
  { "httpMethod": "GET", "uriPattern": "/v1/insights/compliance/**", "description": "...", "category": "COMPLIANCE" }
] }
```

1. In **one transaction**: rows are diffed on `(httpMethod, uriPattern)` — kept rows keep their `id` and
   get the new description/category, removed rows are deleted, new rows inserted — and
   `ROLE_PERMISSIONS_UPDATED` is audited. This commits.
2. **Then** gateway's sync is called (never inside that transaction, so it cannot roll it back).

`200` with `{ "permissions": [...], "syncStatus": "SYNCED" }` when both succeed. If the sync fails:
`502 KEYCLOAK_SYNC_FAILED`, the rows **and** the audit row stay saved — see
[flow-diagrams.md](flow-diagrams.md#flow-4--permission-replacement--sync).

| Status | Cause |
|---|---|
| `400` | `VALIDATION_ERROR` — `httpMethod` not one of `GET POST PUT PATCH DELETE` (upper case); `uriPattern` blank, not starting with `/`, containing spaces or longer than 512; `category` longer than 255; the same `(method, pattern)` twice in one request |
| `404` | `ROLE_NOT_FOUND` |
| `409` | `SYSTEM_ROLE_IMMUTABLE` — `SUPER_ADMIN`'s permissions cannot be edited (removing `/admin/v1/**` would lock every admin out) |
| `409` | `ROLE_IN_USE` — an **empty** set while users still hold the role: gateway's sync deletes any Keycloak role with no rows, which would pull it from under them. An empty set is allowed once nobody holds the role |
| `502` | `KEYCLOAK_SYNC_FAILED` — rows saved, sync did not succeed |

**What counts as a failed sync.** gateway-service's `POST /api/permissions/sync-to-keycloak` is **global**
(no per-role variant): it reloads *every* `api_permissions` row into gateway and pushes *every* distinct
`permission_name` to Keycloak as a realm role, **deleting** realm roles that have no rows (except
`default-roles-*`, `offline_access`, `uma_authorization`). This service treats its answer as follows:

| gateway answers | Result for this role |
|---|---|
| `200` | success |
| `206` (partial) | success **unless this role is in `failedRoles`** — another role's failure doesn't block this one |
| `206` naming this role, `500`, any other status, connection error, timeout (20s default), unreadable body | `502 KEYCLOAK_SYNC_FAILED` |

### `POST /admin/v1/roles/:id/sync`

No body. Re-runs the (global) gateway sync, judged for this role as above, without changing any row — the manual "Re-sync"
action `cce-admin-ui` shows after a `KEYCLOAK_SYNC_FAILED` (F-14, scenario S6). `200` on success with the same `{ permissions, syncStatus: "SYNCED" }` shape as `PUT`,
`502 KEYCLOAK_SYNC_FAILED` if it fails again. Changes no data, so it writes no audit row.

**There is no stored sync status.** gateway cannot report whether a role exists in Keycloak, so the
API does not expose one: `syncStatus` appears only on a response that follows a successful sync, and
`cce-admin-ui` shows "status unknown" until the first Save or Re-sync in a session.

---

## Menus

`/admin/v1/menus`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | Full catalog |
| `POST` | `/` | Create |
| `PUT` | `/:id` | Update |
| `DELETE` | `/:id` | Delete |

`GET` returns the complete catalog (not filtered by caller's roles — that's `GET /me/menus`), ordered
by `sort_order` then `label`, **including inactive menus**. Flat list — no nesting. Each entry:
`{ id, menuKey, label, path, icon, sortOrder, isActive }`.

> **Milestone note:** only `GET` exists so far (Milestone 3 — the Role Detail → Menus tab needs the
> catalog). `POST` / `PUT` / `DELETE` below arrive with Milestone 7.

`POST` / `PUT`:
```json
{ "menuKey": "compliance", "label": "Compliance",
  "path": "/compliance", "icon": "ClipboardDocumentCheckIcon", "sortOrder": 10, "isActive": true }
```

| Status | Cause |
|---|---|
| `409` | `DUPLICATE_MENU_KEY` |

`DELETE` also removes the menu's `role_menus` rows (not the roles themselves). `404 MENU_NOT_FOUND`
for an unknown id.

---

## Operational endpoints

### `GET /health`

Unauthenticated (B-05).

```json
{ "status": "UP" }
```

Best-effort DB status — a DB outage should not make `/health` itself throw; report degraded status
in the body rather than erroring, so load balancers can still reach the endpoint to read it.

### Swagger / OpenAPI

Generated from controllers via springdoc-openapi (B-06); served at the service's standard
`/swagger-ui.html` and `/v3/api-docs`. Covers every endpoint above — if it doesn't, that's a gap in
the controller annotations, not a gap in this document.
