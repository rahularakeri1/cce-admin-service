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
#   PROTOCOL_SERVICE_URI=http://localhost:8091   where /v1/protocol/** is forwarded (Milestone 8)
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

# Protocol management (Milestone 8): the console's Protocols page calls cce-protocol-service THROUGH this
# gateway. The jar's own application.yml routes only /admin/**, so this adds /v1/protocol/** -> the protocol
# service (run it on 8091: 8090 is this gateway). Spring replaces a list property wholesale from the
# highest-priority source, so BOTH routes are spelled out here, admin first, exactly as in the jar's yml.
export PROTOCOL_SERVICE_URI="${PROTOCOL_SERVICE_URI:-http://localhost:8091}"
R=SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_ROUTES
export "${R}_0_ID=admin-service" "${R}_0_URI=$ADMIN_SERVICE_URI" "${R}_0_PREDICATES_0=Path=/admin/**" "${R}_0_FILTERS_0_NAME=JwtValidationFilter"
export "${R}_1_ID=protocol-service" "${R}_1_URI=$PROTOCOL_SERVICE_URI" "${R}_1_PREDICATES_0=Path=/v1/protocol/**" "${R}_1_FILTERS_0_NAME=JwtValidationFilter"

echo "Starting gateway-service on :8090 (db=$DATABASE_URL, keycloak=$KEYCLOAK_SERVER_URL realm=$KEYCLOAK_TARGET_REALM)"
echo "Routes: /admin/** -> $ADMIN_SERVICE_URI, /v1/protocol/** -> $PROTOCOL_SERVICE_URI"
exec java -jar "$JAR"
