#!/usr/bin/env bash
# Provisions the FIRST SUPER_ADMIN for an environment. Idempotent — safe to re-run.
#
#   1. Ensures the Keycloak realm role SUPER_ADMIN exists (gateway-service's
#      /api/permissions/sync-to-keycloak normally creates it; this covers environments where
#      gateway isn't running yet — it's the chicken-and-egg case in architecture-overview.md §9.4).
#   2. Assigns that realm role to an EXISTING Keycloak user (create the user in Keycloak first).
#   3. Inserts the matching local users row (status ACTIVE) and the user_roles link, so the
#      admin-service filter lets that person in.
#   4. Marks that user a SYSTEM account (users.is_system) — read-only in the console, so no other admin
#      can deactivate, edit or demote it — and makes sure the Keycloak account is ENABLED.
#
# Because of 3 and 4 this doubles as the RECOVERY tool: if the bootstrap admin was ever disabled or
# deactivated, re-running it restores it. Run it after the service has started once (Flyway V3 adds the flag).
#
# V2__seed.sql deliberately does NOT do this: no named admin account lives in a migration.
#
# Usage:  scripts/bootstrap-admin.sh [admin-email]        (default: testadmin@example.com)
# Env:    KC_URL (http://localhost:8081)  KC_REALM (cce)  KC_ADMIN (admin)  KC_ADMIN_PASSWORD (admin)
#         PSQL  — command that runs psql against ccedb as the service's DB user. Default talks to
#                 the local docker stack:  docker exec -i cce-local-postgres psql -U cce_user -d ccedb
set -euo pipefail

EMAIL="${1:-testadmin@example.com}"
KC_URL="${KC_URL:-http://localhost:8081}"
KC_REALM="${KC_REALM:-cce}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
PSQL="${PSQL:-docker exec -i cce-local-postgres psql -U cce_user -d ccedb}"
ROLE="SUPER_ADMIN"

json() { python3 -I -c "import sys,json; d=json.load(sys.stdin); $1"; }

TOKEN=$(curl -sf -d "client_id=admin-cli&username=${KC_ADMIN}&password=${KC_ADMIN_PASSWORD}&grant_type=password" \
  "${KC_URL}/realms/master/protocol/openid-connect/token" | json 'print(d["access_token"])')
auth=(-H "Authorization: Bearer ${TOKEN}")
admin_api="${KC_URL}/admin/realms/${KC_REALM}"

# --- 1. realm role -------------------------------------------------------------------------
if curl -sf "${auth[@]}" "${admin_api}/roles/${ROLE}" >/dev/null; then
  echo "Keycloak role ${ROLE}: already exists"
else
  curl -sf "${auth[@]}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"${ROLE}\",\"description\":\"Full access to the admin console\"}" "${admin_api}/roles"
  echo "Keycloak role ${ROLE}: created"
fi

# --- 2. find the Keycloak user and assign the role -------------------------------------------
USER_JSON=$(curl -sf "${auth[@]}" --get --data-urlencode "email=${EMAIL}" --data-urlencode "exact=true" "${admin_api}/users")
USER_ID=$(echo "$USER_JSON" | json 'print(d[0]["id"] if d else "")')
if [ -z "$USER_ID" ]; then
  echo "ERROR: no Keycloak user with email ${EMAIL} in realm ${KC_REALM}. Create it first." >&2
  exit 1
fi
USERNAME=$(echo "$USER_JSON" | json 'print(d[0]["username"])')
FIRST=$(echo "$USER_JSON" | json 'print(d[0].get("firstName") or "Admin")')
LAST=$(echo "$USER_JSON" | json 'print(d[0].get("lastName") or "User")')

ROLE_JSON=$(curl -sf "${auth[@]}" "${admin_api}/roles/${ROLE}")
curl -sf "${auth[@]}" -H 'Content-Type: application/json' -d "[${ROLE_JSON}]" \
  "${admin_api}/users/${USER_ID}/role-mappings/realm"
echo "Keycloak user ${USERNAME}: realm role ${ROLE} assigned"
curl -sf -X PUT "${auth[@]}" -H 'Content-Type: application/json' -d '{"enabled":true}' "${admin_api}/users/${USER_ID}"
echo "Keycloak user ${USERNAME}: enabled"

# --- 3. local rows (single quotes in names doubled for SQL) ----------------------------------
q() { printf "%s" "${1//\'/\'\'}"; }
$PSQL -v ON_ERROR_STOP=1 -q <<SQL
INSERT INTO users (username, email, first_name, last_name, status, is_system)
VALUES ('$(q "$USERNAME")', '$(q "$EMAIL")', '$(q "$FIRST")', '$(q "$LAST")', 'ACTIVE', true)
ON CONFLICT (email) DO UPDATE SET status = 'ACTIVE', is_system = true;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.email = '$(q "$EMAIL")' AND r.name = '${ROLE}'
ON CONFLICT (user_id, role_id) DO NOTHING;
SQL
echo "Local user ${EMAIL}: ACTIVE, system account, with role ${ROLE}"
