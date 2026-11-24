#!/usr/bin/env bash
# Starts the existing gateway-service jar against the LOCAL stack (deploy-scripts/local), so the
# admin console's permission sync (POST /api/permissions/sync-to-keycloak) has a real gateway to call.
# Foreground; Ctrl-C to stop. gateway-service is not part of the compose stack, and its defaults point
# at a different database/Keycloak, hence the overrides below.
#
# NOTE: a sync pushes EVERY distinct api_permissions.permission_name to Keycloak as a realm role and
# DELETES realm roles that have no api_permissions rows (except default-roles-*, offline_access,
# uma_authorization). The first sync therefore creates COMPLIANCE_READ, INSIGHTS_READ, ... in the local
# realm, which is expected.
#
# Usage: scripts/run-gateway-local.sh        (build the jar first: cd ../gateway-service && ./gradlew bootJar)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${GATEWAY_JAR:-$HERE/../../gateway-service/build/libs/gateway-service-0.0.1-SNAPSHOT.jar}"
[ -f "$JAR" ] || { echo "Gateway jar not found: $JAR (build it: cd gateway-service && ./gradlew bootJar)" >&2; exit 1; }

export DATABASE_URL="${DATABASE_URL:-r2dbc:postgresql://localhost:5432/ccedb}"
export DATABASE_USERNAME="${DATABASE_USERNAME:-cce_user}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-cce_pass}"
export JWT_ISSUER="${JWT_ISSUER:-http://localhost:8081/realms/cce}"
export JWT_AUDIENCE="${JWT_AUDIENCE:-account}"
export KEYCLOAK_SERVER_URL="${KEYCLOAK_SERVER_URL:-http://localhost:8081}"
export KEYCLOAK_REALM="${KEYCLOAK_REALM:-master}"            # realm the admin account lives in
export KEYCLOAK_TARGET_REALM="${KEYCLOAK_TARGET_REALM:-cce}" # realm whose roles are synced
export KEYCLOAK_ADMIN_USERNAME="${KEYCLOAK_ADMIN_USERNAME:-admin}"
export KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
export ADMIN_SERVICE_URI="${ADMIN_SERVICE_URI:-http://localhost:8085}"
export LOAD_PERMISSIONS_FROM_DATABASE_ON_STARTUP="${LOAD_PERMISSIONS_FROM_DATABASE_ON_STARTUP:-true}"

echo "Starting gateway-service on :8090 (db=$DATABASE_URL, keycloak=$KEYCLOAK_SERVER_URL realm=$KEYCLOAK_TARGET_REALM)"
exec java -jar "$JAR"
