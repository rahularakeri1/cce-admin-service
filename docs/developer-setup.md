# Developer Setup — Admin Service

Design-stage setup, following milestone M0 of `CCE_Admin_Module_PRD.pdf` §10. The local topology
below is **suggested, not prescribed** by the team's design docs — agree it with your lead before
treating it as fixed, per the PRD's own caveat.

## Prerequisites

- Java 21, Maven
- Node 20+, npm 10+
- Docker (for local Postgres + Keycloak)

## 1. Local environment (milestone M0)

**Use the platform's existing shared local infra — do not stand up a separate stack.**
`deploy-scripts/local/docker-compose.yml` already runs Postgres 16 and Keycloak 26.5.4 for every
service in this workspace:

```bash
cd deploy-scripts/local
cp .env.example .env
docker compose up -d
./scripts/setup.sh    # seeds the cce realm + gateway permissions, idempotent
```

| What | Address | Credentials |
|---|---|---|
| PostgreSQL | `localhost:5432`, db `ccedb` | `cce_user` / `cce_pass` |
| Keycloak | `http://localhost:8081` | `admin` / `admin` (realm `master`); realm `cce` seeded by `setup.sh` |

`cce-admin-service` points at this **same** `ccedb` database as every other service — not a
dedicated database (architecture-overview.md §9.1). It needs two things this stack doesn't create by
default:

1. A **public** Keycloak client `cce-admin-ui` in the `cce` realm, redirect URI
   `http://localhost:3002/*`.
2. A **confidential** service-account client for the backend (used by the Keycloak Admin REST client
   in `client/`), with the Keycloak `manage-users` and `manage-realm` (or narrower, once scoped)
   roles needed to create users and assign realm roles.

Neither exists in `k8s/seeds/cce-realm.json` yet — create both in the Keycloak admin console
(`http://localhost:8081`) until someone adds them to that seed file.

Exit check for M0: log in to the Keycloak account console as a test admin user, and confirm
`psql -h localhost -U cce_user -d ccedb -c '\dt'` shows the platform's existing tables (including
`api_permissions`) before writing this service's own migration against the same database.

Locally, `cce-admin-ui` can proxy `/admin/v1/*` straight to `localhost:8085` via the Vite dev proxy.
Stub the gateway sync call behind the `client/` interface so it can be a no-op or a mock server
locally — there is no need to run `gateway-service` to develop this service end to end.

## 2. Backend — `cce-admin-service`

```bash
mvn spring-boot:run
```

Runs migrations (Flyway) on startup against `ccedb` (same database as every other service), serves on
port `8085`.

| Env var | Default | Notes |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/ccedb` | Same `ccedb` database every other CCE service connects to locally — not a dedicated database |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | — | No local-dev default should be used in a shared environment (B-13) |
| `JWT_ISSUER` | — | Must match `gateway-service` for the same realm |
| `JWT_AUDIENCE` | — | Must match `gateway-service` for the same realm |
| `JWT_ROLES_CLAIM_PATH` | `realm_access.roles` | Confirm against the actually-configured realm — see [architecture-overview.md §9.2](architecture-overview.md#92-role-claim-path--confirm-before-keycloak-provisioning) before assuming the default is correct |
| `KEYCLOAK_*` | — | Admin REST API credentials (service-account client) |

Package layout, build order and testing approach: see
[architecture-overview.md §6 and §8](architecture-overview.md#6-package-layout).

Suggested build order (PRD milestone notes):
1. Scaffold Spring Boot 3 / Java 21 Maven project — Web, Data JPA, Flyway, Postgres driver, OAuth2
   Resource Server, Validation, springdoc.
2. Write `V1__create_admin_service_schema.sql` straight from
   [data-dictionary.md](data-dictionary.md) and `V2__seed.sql` for menus + `SUPER_ADMIN`. Have a
   reviewer diff the columns against the data dictionary.
3. Add a `@RestControllerAdvice` mapping domain exceptions to the error codes table in
   [api-reference.md](api-reference.md#error-codes) early — every later milestone depends on it.
4. Add the JWT re-validation filter/converter that resolves the token's `email` claim to a local user
   and enforces `ACTIVE` status (B-12) — see
   [data-dictionary.md §1.1](data-dictionary.md#11-users--console-accounts) for why this is `email`
   rather than `keycloak_id`/`sub`.
5. Wrap `keycloak-admin-client` behind a `KeycloakGateway` interface in `client/` so service tests
   can mock it without a running Keycloak.

## 3. Frontend — `cce-admin-ui`

```bash
npm run dev    # port 3002
```

| Env var | Default | Notes |
|---|---|---|
| `VITE_KEYCLOAK_URL` | `${origin}/auth` | Keycloak base URL |
| `VITE_KEYCLOAK_REALM` | required | Same realm as the service and `cce-insights-ui` |
| `VITE_KEYCLOAK_CLIENT_ID` | `cce-admin-ui` | Own client, separate from `cce-insights-ui` |
| `VITE_API_BASE_URL` | `""` | Prefixed to `/admin/v1/*` |

`VITE_*` values are inlined at build time — each environment needs its own image build, not a
runtime-injected config (unlike some other services in this platform that use `window._env_`
entrypoint injection — this service deliberately does not).

Scripts: `npm run dev`, `build` (`tsc -b` then Vite), `test`, `test:ci`, `lint`.

Suggested build order:
1. Scaffold with Vite's React + TS template; add Tailwind 4, React Router 7, TanStack Query/Table,
   React Hook Form, Zod, `keycloak-js`, MSW.
2. Build `auth/` first (`initAuth()`, `getToken()`, context), then `api/client.ts` with a typed
   `ApiError`, then MSW handlers mirroring [api-reference.md](api-reference.md)'s examples.
3. Every data-fetching hook lives in `hooks/` and wraps exactly one `api/` function. Pages never
   import `api/` directly (F-01–F-06).
4. Diff against the **last-fetched state only** to enable/disable Save on forms — the payload sent
   is always the complete replacement set, never a partial diff (F-11, F-13, F-14).

## 4. Running the full loop locally

No optimistic UI anywhere (PRD §2 Out of scope, F-06) — every mutation waits for the server, and
queries are invalidated on success rather than patched client-side. This makes a broken local Keycloak
or gateway-sync stub immediately visible as a stuck "pending" button rather than a UI that looks
correct and isn't.

Integration check (milestone E2E): run scenarios S1–S6 from the PRD §3 against the real local service
— not against MSW — before considering the module done.
