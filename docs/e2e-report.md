# End-to-end report (Milestone 11)

Run date: 2026-11-27 · Result: **55 checks passed, 0 failed, 4 informational** · Runtime: about 30 seconds.

This is a deliberate full run of scenarios S1–S9, the failure paths and the open question Q3, against the **real local
stack**: Keycloak, Postgres (`ccedb`), `cce-admin-service`, `gateway-service`, `cce-protocol-service` and
`cce-insights-service`. Nothing is mocked. The runner is [`scripts/e2e/run_e2e.py`](../scripts/e2e/run_e2e.py)
(how to run it: [developer-setup.md](developer-setup.md#end-to-end-run-milestone-11)).

## What was and was not covered

| Covered by the runner (API level) | Not covered — needs a person in a browser |
|---|---|
| Every console endpoint behind S1–S9, with real Keycloak tokens | How the screens look and read (copy, spacing, icons, dimmed and locked states) |
| Keycloak side effects (account, realm roles, disabled state) checked in Keycloak itself | The themed sign-in and sign-out redirects, the "Update password" screen |
| Audit rows counted per mutating call | Drag-and-drop ordering, the icon picker, the Roles Edit/View links |
| The gateway: allow, refuse, CORS, two claim paths | Real latency, and the "No access" and error screens in `cce-insights-ui` |
| S6 by stopping the real gateway | S7's upload of a real protocol (the runner proves the gateway allows the POST; it does not create a protocol) |

A browser click-through checklist is at the end of this page.

## Findings

1. **Q3 is confirmed, not hypothetical.** The deployed gateway reads `resource_access.gateway-service.roles`
   (`deploy-scripts/k8s/base/services/gateway-service.yaml`); the console assigns **realm** roles, and the local gateway reads
   `realm_access.roles`. With the deployed setting, a user created through the console with a working role is **refused at the
   gateway (403)**; the same user is allowed once the same role also exists as a **client role on `gateway-service`** and is mapped to
   them. Everything that reads the database (the admin console, the Insights sidebar) looks correct throughout, so the
   problem would show only as failed data calls. See the Q3 row of the open-questions table in [architecture-overview.md](architecture-overview.md).
2. **The same deployed file says the permission sync is off** ("CCE does not call it"). The console's role flow depends on it:
   saving a role's permissions calls the sync (a failed sync is `502 KEYCLOAK_SYNC_FAILED`), and creating a user with a role
   refuses a role that is not yet a Keycloak realm role (`409 ROLE_NOT_SYNCED`). Locally both work because the sync is on. **Not
   verified against the real UAT or production gateway**; it must be checked there before relying on custom roles.
3. **The `/me` permission rows are what makes the sidebar load through the gateway** (F2). A role without
   `GET /admin/v1/me` and `GET /admin/v1/me/menus` is refused at the gateway, while the same calls straight to the admin
   service work — which is why the local setup (the Insights dev server bypasses the gateway) never shows it.
4. **Gateway error shapes are not clean (F3).** An unknown route answers `500` with a body saying `404 NOT_FOUND`, and a request with
   no token answers `500 Invalid Authorization header`, not `401`. The UIs already treat these as failures; the status codes belong to
   gateway-service and were not changed.
5. **What held up:** every mutating call wrote exactly one audit row; a repeated identical role change wrote none; a deactivated
   user lost token access at once and kept their roles; a limited user is refused by the service itself even with the gateway
   bypassed (S8); a role with no menus gets an empty list; the same token sees a menu change straight away (S9); a failed sync
   keeps the saved rows and re-syncs after recovery (S6).

## Results

### Setup

| Result | Check |
|---|---|
| PASS | direct-grant switch on cce-admin-ui turned on for the run |
| PASS | SUPER_ADMIN bootstrap user can call /me |
| PASS | test gateway A (realm_access.roles) up on 8190 |
| PASS | test gateway B (resource_access.gateway-service.roles) up on 8191 |

### S1 — Sidebar for a real role

| Result | Check |
|---|---|
| PASS | role with 2 insights menus and 3 permission rows saved + synced |
| PASS | user created with that role |
| PASS | GET /me/menus (service direct) returns exactly the role's 2 menus |
| PASS | same call through the gateway (realm-role path) works |
| PASS | SUPER_ADMIN's admin console sidebar has the 4 admin tabs |

### S2 — Admin onboards a user with roles

| Result | Check |
|---|---|
| PASS | POST /users returns 201 with the role |
| PASS | Keycloak account created |
| PASS | Keycloak realm role mapped |
| PASS | local users row + user_roles row exist |
| PASS | exactly one USER_CREATED audit row |
| PASS | duplicate email refused with 409 |

### S3 — Admin creates and configures a role

| Result | Check |
|---|---|
| PASS | role created (201) |
| PASS | menus saved |
| PASS | permissions saved and gateway sync SYNCED |
| PASS | role exists in Keycloak as a realm role after the sync |
| PASS | each of the 5 mutating role calls wrote exactly one audit row |

### S4 — Admin changes a user's roles

| Result | Check |
|---|---|
| PASS | role replaced A -> B (200) |
| PASS | Keycloak: A removed, B added, other mappings untouched |
| PASS | one USER_ROLES_UPDATED audit row |
| PASS | same set again is a no-op: 200 and no new audit row |

### S5 — Admin deactivates (and reactivates) a user

| Result | Check |
|---|---|
| PASS | deactivate (200) |
| PASS | Keycloak account disabled |
| PASS | role assignments kept |
| PASS | disabled user can no longer get a token |
| PASS | one USER_DEACTIVATED audit row |
| PASS | reactivation restores access without re-selecting roles |

### S6 — Gateway sync fails, then re-sync

| Result | Check |
|---|---|
| PASS | gateway down: permission save answers 502 KEYCLOAK_SYNC_FAILED |
| PASS | rows stayed saved in the database despite the failed sync |
| PASS | gateway back: POST /roles/:id/sync re-syncs successfully |

### S7 — Admin grants protocol access

| Result | Check |
|---|---|
| PASS | custom role with the Protocols tab and the 4 permission rows saved + synced |
| PASS | signed-in user's admin sidebar is ONLY Protocols |
| PASS | GET protocols through the gateway is allowed |
| PASS | POST protocols is allowed by the gateway (answer is NOT 401/403; an invalid body is rejected by the protocol service itself) |

### S8 — A limited user is really limited

| Result | Check |
|---|---|
| PASS | service refuses /admin/v1/users for the limited user even with the gateway bypassed |
| PASS | gateway refuses /admin/v1/users for the limited user |
| PASS | service refuses /admin/v1/roles for the limited user even with the gateway bypassed |
| PASS | gateway refuses /admin/v1/roles for the limited user |
| PASS | service refuses /admin/v1/menus for the limited user even with the gateway bypassed |
| PASS | gateway refuses /admin/v1/menus for the limited user |

### S9 — Role-aware Insights

| Result | Check |
|---|---|
| PASS | user holding only Dashboard gets exactly [Dashboard] |
| PASS | an Insights data call through the gateway works for that role |
| PASS | after an admin ticks Events, the SAME token sees [Dashboard, Events] (database, not the login ticket) |
| PASS | a user whose role has no menus gets an empty list (the UI shows 'No access') |

### F1 — User in Keycloak but not in the admin table (Q4)

| Result | Check |
|---|---|
| PASS | a Keycloak-only user (not in admin users table) is refused by /me (Q4) |

### F2 — Role without the /me permission rows

| Result | Check |
|---|---|
| PASS | a role WITHOUT the /me permission rows is refused at the gateway (empty sidebar in the UI) |
| PASS | the same call straight to the admin service works (why local never shows the problem) |

### F3 — Gateway answers for unknown route / no token

| Result | Check |
|---|---|
| info | unknown gateway route answers: `500 {'message': '404 NOT_FOUND "No static resource v1/does-not-exist."', 'status': 5` |
| info | no token at all answers: `500 {'message': 'Invalid Authorization header', 'status': 500}` |

### F4 — CORS

| Result | Check |
|---|---|
| PASS | a browser POST from an origin not in ALLOWED_ORIGINS is refused |
| PASS | the same POST from an allowed origin is judged on its token/permissions instead (not blocked by CORS) |

### Q3 — Production-style role claim path

| Result | Check |
|---|---|
| info | token claims of a console-created user: `{'realm_access': {'roles': ['E2E_S1_73F659', 'default-roles-cce', 'offline_access', 'uma_authorization']}, 'resource_access': ['account']}` |
| PASS | gateway A (realm roles, local default): the console-assigned role works |
| PASS | gateway B (client roles, as deployed in k8s): the SAME user is refused |
| info | after adding the client role, token has: `{'roles': ['E2E_S1_73F659']}` |
| PASS | gateway B: with the client role on gateway-service the user is allowed |

## How the run protects your data

The runner creates only `e2e-*` users, `E2E_*` roles and their permission rows, removes them (and the audit rows they caused) at the end,
turns `cce-admin-ui`'s direct-access-grants switch on for the run and puts it back to its original value, and finishes with
a gateway reload so no test rows remain loaded. After the final run the leftover counts were 0 users, 0 roles and 0 permission rows.
Scenario S6 stops the gateway on port 8090 for about a minute and restarts it with the same environment; use `--skip-s6` to leave it alone.

## Browser click-through (manual)

Use two private windows (the admin console and Insights share one sign-in session).

- [ ] `http://localhost:3002` redirects to the themed admin sign-in; sign in as a SUPER_ADMIN; the sidebar shows Users, Roles, Menus, Protocols
- [ ] Roles: custom roles show **Edit**, SUPER_ADMIN shows **View**; SUPER_ADMIN's Menus tab is fully ticked and locked with no Save
- [ ] Menus: no Add and no Delete; reorder by dragging and Save order; edit a label and an icon with the picker; `/menus/new` returns to the table
- [ ] Users: create a user with a role (the invite email step is not part of the automated run)
- [ ] `http://localhost:3001` redirects to the Insights sign-in; a role with two menus sees exactly those; a typed address for another page redirects
- [ ] Change that role's menus in the admin console, return to the Insights tab: the sidebar updates without signing in again
- [ ] A role with no menus sees "No access"; sign out returns to the app root, not a blank page
- [ ] Protocols: list, view, upload and retire a protocol as a SUPER_ADMIN and as a role holding only the Protocols tab
