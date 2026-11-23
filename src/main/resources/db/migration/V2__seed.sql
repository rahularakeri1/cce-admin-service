-- V2: reference data nothing else can bootstrap from (docs/data-dictionary.md §3, Milestone 2).
--
--   * the 7 real cce-insights-ui sidebar menus (cce-insights-ui/src/components/layout/Sidebar.tsx)
--   * the SUPER_ADMIN system role, linked to every seeded menu
--   * api_permissions rows (INSERT only — gateway-service owns that table's DDL) so SUPER_ADMIN can
--     call this service through the gateway, including GET /admin/v1/me/menus itself
--
-- NOT seeded here, on purpose: any named admin account. The first SUPER_ADMIN is provisioned per
-- environment by scripts/bootstrap-admin.sh (Keycloak realm role + local users/user_roles rows).
-- Idempotent: safe to re-run against a database that already holds some of these rows.

INSERT INTO menus (menu_key, label, path, icon, sort_order) VALUES
    ('dashboard',   'Dashboard',   '/',                    'ChartBarIcon',                1),
    ('facilities',  'Facilities',  '/facilities',          'BuildingOffice2Icon',         2),
    ('compliance',  'Compliance',  '/compliance',          'ClipboardDocumentCheckIcon',  3),
    ('deviations',  'Deviations',  '/deviations',          'ExclamationTriangleIcon',     4),
    ('patients',    'Patients',    '/compliance/patients', 'ClipboardDocumentCheckIcon',  5),
    ('events',      'Events',      '/events',              'SignalIcon',                  6),
    ('ingestion',   'Ingestion',   '/ingestion',           'CogIcon',                     7)
ON CONFLICT (menu_key) DO NOTHING;

INSERT INTO roles (name, display_name, description, is_system) VALUES
    ('SUPER_ADMIN', 'Super Administrator',
     'Full access to the admin console and every seeded menu. System role: cannot be renamed or deleted.',
     true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
FROM roles r
CROSS JOIN menus m
WHERE r.name = 'SUPER_ADMIN'
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- api_permissions has no unique constraint on (method, pattern), so guard each row explicitly.
-- permission_name doubles as the Keycloak realm role name gateway-service syncs/checks against.
INSERT INTO api_permissions (permission_name, http_method, uri_pattern, description, resource_category)
SELECT 'SUPER_ADMIN', v.http_method, v.uri_pattern, v.description, 'Admin'
FROM (VALUES
    ('GET',    '/admin/v1/me',        'Admin console: current user profile'),
    ('GET',    '/admin/v1/me/menus',  'Admin console: menus for the current user'),
    ('GET',    '/admin/v1/**',        'Admin console: read access'),
    ('POST',   '/admin/v1/**',        'Admin console: create access'),
    ('PUT',    '/admin/v1/**',        'Admin console: update access'),
    ('PATCH',  '/admin/v1/**',        'Admin console: partial update access'),
    ('DELETE', '/admin/v1/**',        'Admin console: delete access')
) AS v (http_method, uri_pattern, description)
WHERE NOT EXISTS (
    SELECT 1 FROM api_permissions p
    WHERE p.permission_name = 'SUPER_ADMIN'
      AND p.http_method = v.http_method
      AND p.uri_pattern = v.uri_pattern
);
