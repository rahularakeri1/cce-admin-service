#!/usr/bin/env bash
# Local dev only: starts Mailpit (a mail catcher) on the same Docker network as the local Keycloak and
# points the realm's SMTP settings at it, so the invitation email sent when a user is created
# (VERIFY_EMAIL + UPDATE_PASSWORD) actually goes somewhere you can open. Without SMTP, every user
# creation succeeds with the warning "Invitation email could not be sent".
#
#   Web UI:  http://localhost:8025   (every email Keycloak sends shows up here, with its link)
#
# Idempotent. Never used in a real environment — there the realm's SMTP is configured by operators.
# Env: KC_URL (http://localhost:8081)  KC_REALM (cce)  KC_ADMIN / KC_ADMIN_PASSWORD (admin/admin)
#      DOCKER_NETWORK (cce-net)  MAIL_CONTAINER (cce-local-mailpit)
set -euo pipefail

KC_URL="${KC_URL:-http://localhost:8081}"
KC_REALM="${KC_REALM:-cce}"
KC_ADMIN="${KC_ADMIN:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
NET="${DOCKER_NETWORK:-cce-net}"
NAME="${MAIL_CONTAINER:-cce-local-mailpit}"
IMAGE="${MAILPIT_IMAGE:-axllent/mailpit:v1.21}"

if [ "$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null || echo missing)" != "true" ]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker run -d --name "$NAME" --network "$NET" --restart unless-stopped \
    -p 8025:8025 -p 1025:1025 "$IMAGE" >/dev/null
  echo "Mailpit started: web http://localhost:8025, smtp ${NAME}:1025 on network ${NET}"
else
  echo "Mailpit already running"
fi

TOKEN=$(curl -sf -d "client_id=admin-cli&username=${KC_ADMIN}&password=${KC_ADMIN_PASSWORD}&grant_type=password" \
  "${KC_URL}/realms/master/protocol/openid-connect/token" | python3 -I -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')

curl -sf -X PUT -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"smtpServer\":{\"host\":\"${NAME}\",\"port\":\"1025\",\"from\":\"no-reply@cce.local\",\"fromDisplayName\":\"CCE Admin\",\"auth\":\"false\",\"ssl\":\"false\",\"starttls\":\"false\"}}" \
  "${KC_URL}/admin/realms/${KC_REALM}"
echo "Realm ${KC_REALM}: SMTP -> ${NAME}:1025"
