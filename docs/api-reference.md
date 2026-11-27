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
| 409 | `MENU_KEY_IMMUTABLE` / `ADMIN_MENU_PROTECTED` | A menu's key changed, or a protected admin tab deleted, deactivated or moved — see [Menus](#menus) |
| 409 | `MENU_ORDER_OUTDATED` | `PUT /menus/order` with a list that is no longer that console's exact set of entries — see [Menus](#menus) |
| 409 | `ADMIN_MENU_REQUIRED` / `ADMIN_MENU_SUPER_ADMIN_ONLY` | A role's menu set breaks the admin tab rules — see `PUT /roles/:id/menus` |
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

> **Milestone 7.** Accepts `?console=INSIGHTS|ADMIN` (default `INSIGHTS`, so callers that predate the parameter are unchanged; any other value is `400 VALIDATION_ERROR`). `cce-admin-ui` calls it with `console=ADMIN` to build its own sidebar — see [architecture-overview.md §9.9](architecture-overview.md#99-milestones-78--role-aware-console-and-protocol-management). `?console=ADMIN` returns the caller's own admin tabs (Users, Roles, Menus, Protocols — whichever their roles carry); a role without them gets `[]`. Read from the database on every call, so a role or tab change shows on the next request without a token refresh.

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

All the endpoints below that also change Keycloak work **local-first with rollback**, like creation: a
database transaction opens, the local change and its audit row are written, the Keycloak calls follow,
and the transaction commits last. If a Keycloak call fails, the ones already applied are **reversed**
(best effort) and the local transaction rolls back — nothing persists, `502 KEYCLOAK_SYNC_FAILED`. If the
local commit fails after Keycloak changed, Keycloak is reversed the same way. If a *reversal* fails,
Keycloak and the database may now disagree, so a `USER_SYNC_FAILED` row is written in its own transaction
(see [data-dictionary.md §2.2](data-dictionary.md#22-admin_audit_logaction)); there is no automatic
repair. See [flow-diagrams.md Flow 3](flow-diagrams.md#flow-3--role-assignment-diff).

Keycloak accounts are found by **username**, never by email: no `keycloak_id` is stored (see
[data-dictionary.md §1.1](data-dictionary.md#11-users--console-accounts)), username is permanent, and email
is editable. A local user with no matching Keycloak account fails with `502`.

### `GET /admin/v1/users/:id`

The same shape as a `POST` response (`roles` as role names, `isSystem`, `lastLoginAt`). `404 USER_NOT_FOUND` for an
unknown id, `400` for a malformed one.

### `PUT /admin/v1/users/:id`

```json
{ "firstName": "...", "lastName": "...", "email": "...", "facilityScope": "..." }
```

Profile only — not username (permanent), `status` or roles. All three names/email are required;
`facilityScope` may be null. Email is lower-cased.

- **Changing the email** (a) must not belong to another user → `409 EMAIL_IN_USE` with zero Keycloak
  calls; (b) sets it in Keycloak and marks it **unverified**; (c) after the commit, sends a `VERIFY_EMAIL`
  email to the *new* address. A failed send does not fail the request: `200` with a `warnings` entry. The
  user's current session still carries the old address in its token, so their requests are refused with
  `403` until the token refreshes (a few minutes) or they sign in again — the cost of using email as the
  join key.
- An unchanged email leaves Keycloak's verified flag alone and sends nothing.
- Audits `USER_UPDATED` with before/after of the four fields.

### `PATCH /admin/v1/users/:id/status`

```json
{ "status": "INACTIVE" }
```

`ACTIVE` or `INACTIVE` only (`LOCKED` does not exist — data-dictionary.md §2.1). **Deactivating** sets
local `status = INACTIVE`, **disables** the Keycloak account **and ends all its sessions**, so an
already-issued refresh token stops working at once. `user_roles` rows are **kept**. **Re-activating**
re-enables the account and restores access without re-selecting roles (B-34). Audits `USER_DEACTIVATED` /
`USER_REACTIVATED`.

Effect timing: this service refuses the user from the very next request (the local status is checked on
every call). A token already issued stays valid for other services until it expires (minutes), because
they only validate the token — ending the Keycloak sessions stops *refresh*, not an unexpired access token.

Asking for the status the user already has is a no-op: `200`, no Keycloak call, no audit row.

| Status | Cause |
|---|---|
| `409` | `SYSTEM_USER_IMMUTABLE` — the target is a system account |
| `409` | `CANNOT_DEACTIVATE_SELF` — you cannot deactivate your own account |
| `409` | `LAST_SUPER_ADMIN` — would leave no active `SUPER_ADMIN` |
| `404` / `400` | unknown user / invalid `status` |
| `502` | `KEYCLOAK_SYNC_FAILED` — nothing changed |

### `PUT /admin/v1/users/:id/roles`

```json
{ "roleIds": ["...", "..."] }
```

**Full-replacement semantics** — the payload is the complete desired set, not a delta (empty = no roles).
The service diffs it against the current `user_roles` rows and assigns/removes **only the realm roles that
actually changed** in Keycloak (B-35) — a user with 5 roles who gains a 6th causes exactly one assign call
and no removes; see [flow-diagrams.md](flow-diagrams.md#flow-3--role-assignment-diff). The same set again
is a no-op (`200`, no Keycloak call, no audit row). Audits `USER_ROLES_UPDATED` with the before/after role
*names*.

| Status | Cause |
|---|---|
| `400` | unknown `roleId` |
| `409` | `SYSTEM_USER_IMMUTABLE` — the target is a system account |
| `409` | `ROLE_NOT_SYNCED` — a role being **added** is not a Keycloak realm role yet (checked before anything is written); open its Permissions tab and Save or Re-sync first |
| `409` | `LAST_SUPER_ADMIN` — removing `SUPER_ADMIN` from the only active holder |
| `502` | `KEYCLOAK_SYNC_FAILED` — local roles unchanged; any Keycloak change already made was reversed |

### `POST /admin/v1/users/:id/reset-password`

No body. Sends the same combined `VERIFY_EMAIL` + `UPDATE_PASSWORD` action email `POST /users` sends at
creation (72-hour link); no password ever passes through this service (B-36). `200` with `{ "sent": true }`.
Each call issues a fresh link and invalidates any earlier unused one. Audits `USER_PASSWORD_RESET`, only
when the email was actually sent.

Serves two purposes with the same call: **resend** (the creation email expired, was lost, or failed) and
**genuine reset** (a security concern for an already-onboarded user).

| Status | Cause |
|---|---|
| `409` | `USER_NOT_ACTIVE` — an inactive account can't use the link; reactivate first |
| `502` | `KEYCLOAK_SYNC_FAILED` — unlike creation (where a failed email is a warning), the email *is* the whole operation here |
| `404` | unknown user |

`cce-admin-ui` surfaces this more prominently — a "needs attention" indicator with a **Resend invite**
button — for any user with `status = ACTIVE` and `last_login_at IS NULL` (never completed onboarding; see
[data-dictionary.md §1.1](data-dictionary.md#11-users--console-accounts)); every other user has it on
their detail page.

**Lockout guards (not in the PRD).** The first admin is created by hand, so the console refuses actions
that would leave nobody able to administer it:

- **System accounts are read-only.** A user with `isSystem = true` (the bootstrap admin) cannot be edited,
  deactivated or have its roles changed by **anyone** — `PUT /users/:id`, `PATCH /users/:id/status` (even to
  its current value) and `PUT /users/:id/roles` all answer `409 SYSTEM_USER_IMMUTABLE`, before any Keycloak
  call. Checked first, ahead of every rule below. **Password reset is the one exception**: it changes
  nothing about the account and is the recovery path if that admin forgets their password. Users created
  through the console are never system accounts.
- A user cannot deactivate **themselves**.
- The **last active `SUPER_ADMIN`** cannot be deactivated or have the role removed.

A system account that was disabled out of band is restored by re-running `scripts/bootstrap-admin.sh`.

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
the admin console's own tabs are menu entries since Milestone 7, so two rules apply — both `409`, both
checked before anything is saved or audited:

| Rule | Code |
|---|---|
| `SUPER_ADMIN`'s four admin entries (Users, Roles, Menus, Protocols) cannot be removed — a request that omits any of them is refused, so nobody can lock themselves out | `ADMIN_MENU_REQUIRED` |
| The **Users, Roles and Menus** admin entries can only be assigned to `SUPER_ADMIN` — assigning one to any other role is refused, because every call behind them is `SUPER_ADMIN`-only | `ADMIN_MENU_SUPER_ADMIN_ONLY` |

The Protocols entry and the insights entries stay assignable to any role (insights entries stay editable on
`SUPER_ADMIN` too). The UI greys out what the server would refuse. Audits `ROLE_MENUS_UPDATED` (before/after
menu ids).

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
| `PUT` | `/order` | Arrange the entries of one console (drag and drop in the UI) |
| `DELETE` | `/:id` | Delete |

`GET` returns the complete catalog (not filtered by caller's roles — that's `GET /me/menus`), ordered
insights menus first, then the admin console's tabs (each by `sort_order`, then `label`), **including inactive menus**.
Flat list — no nesting. Each entry: `{ id, menuKey, label, path, icon, console, sortOrder, isActive }`, where
`console` is `INSIGHTS` or `ADMIN`.

`POST` creates and `PUT /:id` updates; both take the same body and answer `{ "data": <the entry> }` (`201` / `200`):

```json
{ "menuKey": "reports", "label": "Reports",
  "path": "/reports", "icon": "ChartBarIcon", "isActive": true }
```

| Field | Rule |
|---|---|
| `menuKey` | Required, up to 100 chars: lower-case letters, digits, `-` or `_`, starting with a letter or digit (`reports`, `admin-audit`). **Permanent**: on `PUT` it must equal the existing key |
| `label` | Required, up to 150 chars — what the sidebar shows |
| `path` | Required, up to 255 chars, starts with `/` |
| `icon` | Optional, up to 100 chars (a Heroicons name); blank is stored as none |
| `sortOrder` | Optional, `>= 0`. Missing: **the end of that console's list** on create (one past the current last), **unchanged** on update. The console arranges entries with `PUT /menus/order` below and no longer sends it |
| `isActive` | Optional. Missing: `true` on create, **unchanged** on update. `false` is "deactivate" |
| `console` | **Ignored** if sent. New entries are always `INSIGHTS` (an admin tab needs a screen built in code, so those come from migrations); an existing entry's console never changes |

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A field above is invalid |
| `404` | `MENU_NOT_FOUND` | Unknown id (`PUT`, `DELETE`) |
| `409` | `DUPLICATE_MENU_KEY` | `POST` with a key that exists (also if two admins race: the unique constraint decides) |
| `409` | `MENU_KEY_IMMUTABLE` | `PUT` with a different `menuKey` — roles and code refer to an entry by key |
| `409` | `ADMIN_MENU_PROTECTED` | See below |

**The four admin console tabs are protected** (Users, Roles, Menus, Protocols — every entry whose `console` is
`ADMIN`). This console builds its own sidebar from them, so removing or hiding one would lock everyone out of a
screen with no way back short of editing the database. For these entries `DELETE` is `409 ADMIN_MENU_PROTECTED`,
`PUT` with `isActive: false` is `409 ADMIN_MENU_PROTECTED`, and `PUT` with a different `path` is `409
ADMIN_MENU_PROTECTED`. Their **label, icon and sort order stay editable**.

**Arranging: `PUT /admin/v1/menus/order`.** Sets the order of **one console's** entries in a single call — what the
Menus screen's drag-and-drop saves. The body is the **complete** ordered list for that console, never a partial move:

```json
{ "console": "INSIGHTS", "menuIds": ["<id of the first entry>", "<id of the second>", "..."] }
```

The service renumbers that console's `sort_order` to `1..n` in the order given, in one transaction, and answers
`200` with the full catalog (the same list as `GET /menus`) so the caller can show the result. The admin console's
own tabs can be arranged too (it only changes the order of this console's sidebar); the protection rules above
don't apply to order. Saving the order it already has succeeds.

| Status | Code | Cause |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `console` missing or not `INSIGHTS` / `ADMIN`; `menuIds` missing or containing a duplicate |
| `409` | `MENU_ORDER_OUTDATED` | `menuIds` is not exactly that console's current set of entries: one was added, deleted or belongs to the other console since the caller loaded the list. Nothing is changed; the caller reloads and arranges again |

The exact-set rule is deliberate: an entry another admin adds while you are dragging is never silently dropped or
sent to a stale position. Audits `MENU_ORDER_UPDATED` (the console and the ordered ids before and after).

**Deactivate vs delete.** Deactivating (`PUT` with `isActive: false`) keeps the entry in the catalog and **keeps its
role assignments**: it simply stops appearing in `GET /me/menus` for everyone, and activating it again restores it
for the same roles. `DELETE` is permanent and removes the entry **and exactly its own `role_menus` rows** — every
role that held it loses it, nothing else changes, the roles themselves stay (`204`).

Audits `MENU_CREATED` / `MENU_UPDATED` (before/after), `MENU_ORDER_UPDATED` and `MENU_DELETED` (the entry plus
`roleAssignmentsRemoved`, how many roles lost it). Like everything under `/admin/v1` except `/me` and `/health`,
these need `SUPER_ADMIN`.

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

---

## Authorization (Milestone 7)

The service refuses what a role may not do, independent of the gateway, using the user's **database roles**
(read from `user_roles` on every request, not the token's role claim — see [architecture-overview.md §9.9](architecture-overview.md#99-milestones-78--role-aware-console-and-protocol-management)):

| Endpoints | Required | Otherwise |
|---|---|---|
| `/admin/v1/health`, Swagger | none | |
| `/admin/v1/me`, `/admin/v1/me/menus` | a signed-in, `ACTIVE` user | `401` / `403` |
| every other `/admin/v1/**` (users, roles, menus, permissions) | `SUPER_ADMIN` | `403 FORBIDDEN` |

---

## Protocols (Milestone 8)

**This service has no protocol endpoints.** `cce-admin-ui`'s Protocols tab calls `cce-protocol-service`'s own
API **through the gateway** with the user's token:

| Method | Path (through the gateway) | Used for |
|---|---|---|
| `GET` | `/v1/protocol/protocol-definitions` | List, paged (`page`, `size`, `sort`) |
| `GET` | `/v1/protocol/protocol-definitions/{id}` | One definition |
| `POST` | `/v1/protocol/protocol-definitions` | Upload — body `{ "planDefinitionJson": "<the resource as a string>" }` |
| `POST` | `/v1/protocol/protocol-definitions/{id}/retire` | Retire |

Not in v1: delete, rebuild-index, action definitions. Full request/response details are in
[cce-protocol-service's API reference](../../cce-protocol-service/docs/api-reference.md).

A role may use them only if it has the **Protocols** menu **and** gateway permission rows allowing the calls.
`SUPER_ADMIN` gets both from migrations (V4 and V5). For any other role a `SUPER_ADMIN` adds four rows on the
role's Permissions tab: `GET /admin/v1/me`, `GET /admin/v1/me/menus`, and `GET` and `POST
/v1/protocol/protocol-definitions/**` — see
[architecture-overview.md §9.9](architecture-overview.md#99-milestones-78--role-aware-console-and-protocol-management).

**What comes back** (checked live through the gateway; the UI decides by HTTP status first):

| Call | Success | Failure |
|---|---|---|
| list | `200`, a Spring `Page`: `content[]`, `totalElements`, `totalPages`, `number`, `size`, `first`, `last` | — |
| detail | `200`, `{ id, url, version, canonical, status, loadedAt, definition }` (`status` `ACTIVE` / `RETIRED`) | `404` unknown id |
| upload | `201`, the new definition (`ACTIVE`) | `400` the same `(url, version)` exists — message `Protocol definition already exists for url=…, version=…` (**400, not 409**); `400` blank body; `422` not FHIR JSON / failed protocol validation |
| retire | `200`, the definition now `RETIRED` | `409` already retired; `404` unknown id |

Errors from the protocol service look like `{ status, error, message, timestamp }`. Two other answers come from
the **gateway**, not the protocol service, and have a different body: **`403`** `{ error, message: "Access
denied: Insufficient permissions", status }` when the role has no matching row (and an **empty `403`** when the
caller's browser `Origin` is not in the gateway's `ALLOWED_ORIGINS` — refused by CORS before any permission check;
see [deployment-guide.md](deployment-guide.md#protocol-management-milestone-8)), and **`500`** with no
`message` (only `timestamp, path, status, error, requestId`) when the protocol service is **down** — the gateway
does not translate that to 502/503. A request with no token is also a gateway `500` ("Invalid Authorization
header"). So the UI treats any `5xx` or a network failure as "protocol service unavailable".

Retiring sets the status to `RETIRED` **and deletes the protocol's trigger-index entries**, so it stops matching
new events; existing enrollments are untouched. There is no un-retire in this release (open question Q6).

Who uploaded or retired what is **not recorded** in this release (open question Q1, §9.10).
