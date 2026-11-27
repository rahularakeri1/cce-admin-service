-- V5: gateway permission rows so SUPER_ADMIN can use the Protocols tab (docs/data-dictionary.md §3, Milestone 8).
--
-- The admin console's Protocols page calls cce-protocol-service THROUGH the gateway, with the user's own
-- token, so gateway-service's api_permissions rows are the only gate on those calls (nothing in this service
-- sits in that path). INSERT only: gateway-service owns that table's DDL.
--
--   GET  /v1/protocol/protocol-definitions/**   list, view
--   POST /v1/protocol/protocol-definitions/**   upload, retire
--
-- Deliberately NOT granted: PUT / PATCH / DELETE (this release has no edit or delete), and no role is seeded:
-- any other role is made by a SUPER_ADMIN (Protocols tab + these rows + GET /admin/v1/me and /me/menus).
-- After this migration run the gateway sync (POST /api/permissions/sync-to-keycloak, or save any role's
-- Permissions tab) so the gateway reloads its rules.
-- Idempotent: api_permissions has no unique constraint on (method, pattern), so each row is guarded explicitly.

INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category)
SELECT 'SUPER_ADMIN', v.http_method, v.uri_pattern, v.description, 'Protocol'
FROM (VALUES
    ('GET',  '/v1/protocol/protocol-definitions/**', 'Protocol management: list and view protocol definitions'),
    ('POST', '/v1/protocol/protocol-definitions/**', 'Protocol management: upload and retire protocol definitions')
) AS v (http_method, uri_pattern, description)
WHERE NOT EXISTS (
    SELECT 1 FROM api_permissions p
    WHERE p.permission_name = 'SUPER_ADMIN'
      AND p.http_method = v.http_method
      AND p.uri_pattern = v.uri_pattern
);
