# Deployment Guide — Admin Service

Design-stage. Both `cce-admin-service` and `cce-admin-ui` are new deployables; nothing below has been
run in a real environment yet.

## Images

| Component | Build | Runtime | Port |
|---|---|---|---|
| `cce-admin-service` | Multi-stage Maven build | JRE image | `8085` |
| `cce-admin-ui` | `node:20-alpine` build → static assets | `caddy:2-alpine` | `3002` |

`cce-admin-ui`'s `VITE_*` values are baked in at build time (developer-setup.md §3), so — like
`cce-insights-ui` — **each environment needs its own image build**, not a shared image with
runtime-injected config.

## Network

In every non-local environment, both apps reach `cce-admin-service` only through `gateway-service`;
the service itself is not internet-facing (PRD §9 Non-functional — Network). Do not expose port
`8085` directly outside the cluster/VM network `gateway-service` also sits on.

## Config parity with gateway-service

`JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_ROLES_CLAIM_PATH` must match `gateway-service`'s configuration for
the same realm (PRD §9). A mismatch here means this service and the gateway disagree about who a
caller is even though both validated the same token successfully — confirm these three values first
whenever standing up a new environment, and see
[architecture-overview.md §9.2](architecture-overview.md#92-role-claim-path--confirm-before-keycloak-provisioning)
for why `JWT_ROLES_CLAIM_PATH` specifically needs a live check, not just copying the default.

## CI

Jenkins CI (per PRD §6). Definition of done per PRD §11 — required before merge, not just before
deploy:
- `npm run build` / `npm run lint` / `mvn verify` pass.
- No secrets, `.env` files, or local Keycloak exports committed.
- Tests cover the happy path and at least one error code per endpoint/screen touched that PR.

## Database

`ccedb`, `public` schema — the same shared database/schema every other CCE service already deploys
into, confirmed against the real dev instance (not the separate `admin_service` database the PRD's
diagram shows — see
[architecture-overview.md §9.1](../docs/architecture-overview.md#91-schema-target-and-api_permissionsid-type--resolved)).
This service's own `flyway_schema_history_admin` ledger tracks only the six tables it owns; it does
not migrate `api_permissions`, which `gateway-service` already owns in the same schema. Deploying this
service therefore needs the **same** `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB` (`ccedb`) config
every other service already gets from the shared `cce-config` ConfigMap — no new database
provisioning step.

## First deploy to a new environment

Order matters because of the bootstrap gap noted in
[architecture-overview.md §9.4](architecture-overview.md#94-bootstrap-sequencing--no-concrete-owner-yet) —
there is no UI path to create the first admin:

1. Run migrations (`V1` schema + `V2` seed — menus, `SUPER_ADMIN` role, bootstrap `api_permissions`
   rows for `/admin/v1/**` and `/admin/v1/me/menus`).
2. Provision the first `SUPER_ADMIN` user — create the user in Keycloak, then run
   `scripts/bootstrap-admin.sh <email>` (set `KC_URL`, `KC_ADMIN_PASSWORD` and `PSQL` for the target
   environment). It assigns the realm role and inserts the local `users` + `user_roles` rows; it is
   idempotent. There is no self-service path.
3. Confirm `GET /health` is reachable and unauthenticated (B-05) before wiring up monitoring that
   depends on it.
4. Log in to `cce-admin-ui` as the bootstrap `SUPER_ADMIN` and run scenario S1 (sidebar loads) as the
   smoke test for the whole chain: Keycloak → gateway → admin-service → DB.
