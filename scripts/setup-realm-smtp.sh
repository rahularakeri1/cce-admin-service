#!/usr/bin/env bash
# Points a Keycloak realm's SMTP settings at a REAL mail server, so the invitation email sent when a
# user is created (VERIFY_EMAIL + UPDATE_PASSWORD) lands in a real inbox.
#
# Your credentials stay in YOUR shell: pass them as environment variables when you run this — don't
# paste them anywhere else.
#
#   SMTP_HOST=smtp.gmail.com SMTP_PORT=587 SMTP_STARTTLS=true \
#   SMTP_USER=you@gmail.com SMTP_PASSWORD='your-app-password' \
#   SMTP_FROM=you@gmail.com  scripts/setup-realm-smtp.sh
#
# Common setups
#   Gmail / Google Workspace  host smtp.gmail.com  port 587  starttls   user = your address, password = an
#                             APP PASSWORD (needs 2-step verification; your normal password will not work)
#   Microsoft 365             host smtp.office365.com  port 587  starttls  (SMTP AUTH must be enabled for the mailbox)
#   SendGrid / Mailgun / SES  use their SMTP host, port 587, and the API-key style user/password they give you
#
# Required: SMTP_HOST, SMTP_FROM.  Optional: SMTP_PORT (587), SMTP_USER + SMTP_PASSWORD (auth is on only if
#   both are set), SMTP_STARTTLS (true), SMTP_SSL (false — set true for port 465), SMTP_FROM_NAME (CCE Admin).
# Env for Keycloak itself: KC_URL (http://localhost:8081)  KC_REALM (cce)  KC_ADMIN / KC_ADMIN_PASSWORD (admin/admin)
#
# To go back to the local mail catcher:  scripts/setup-local-mail.sh
set -euo pipefail

: "${SMTP_HOST:?set SMTP_HOST (e.g. smtp.gmail.com)}"
: "${SMTP_FROM:?set SMTP_FROM (the sender address)}"
SMTP_PORT="${SMTP_PORT:-587}"
SMTP_STARTTLS="${SMTP_STARTTLS:-true}"
SMTP_SSL="${SMTP_SSL:-false}"
SMTP_FROM_NAME="${SMTP_FROM_NAME:-CCE Admin}"
KC_URL="${KC_URL:-http://localhost:8081}"
KC_REALM="${KC_REALM:-cce}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"

TOKEN=$(curl -sf -d "client_id=admin-cli&username=${KC_ADMIN}&password=${KC_ADMIN_PASSWORD}&grant_type=password" \
  "${KC_URL}/realms/master/protocol/openid-connect/token" | python3 -I -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

# Build the JSON with python so passwords containing quotes/backslashes are encoded correctly.
BODY=$(python3 -I - <<'PY'
import json, os
e = os.environ.get
auth = bool(e("SMTP_USER") and e("SMTP_PASSWORD"))
smtp = {
    "host": e("SMTP_HOST"), "port": e("SMTP_PORT", "587"),
    "from": e("SMTP_FROM"), "fromDisplayName": e("SMTP_FROM_NAME", "CCE Admin"),
    "starttls": e("SMTP_STARTTLS", "true"), "ssl": e("SMTP_SSL", "false"),
    "auth": "true" if auth else "false",
}
if auth:
    smtp["user"], smtp["password"] = e("SMTP_USER"), e("SMTP_PASSWORD")
print(json.dumps({"smtpServer": smtp}))
PY
)
export SMTP_HOST SMTP_PORT SMTP_FROM SMTP_FROM_NAME SMTP_STARTTLS SMTP_SSL SMTP_USER SMTP_PASSWORD 2>/dev/null || true

curl -sf -X PUT -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -d "$BODY" "${KC_URL}/admin/realms/${KC_REALM}"
echo "Realm ${KC_REALM}: SMTP -> ${SMTP_HOST}:${SMTP_PORT} (from ${SMTP_FROM}, auth: $([ -n "${SMTP_USER:-}" ] && [ -n "${SMTP_PASSWORD:-}" ] && echo on || echo off))"
echo "Test it: create a user in the console with an address you own, or in Keycloak: Realm settings → Email → Test connection."
