-- V4: the admin console's own tabs live in the same menus catalog as cce-insights-ui's
-- (docs/data-dictionary.md §1.3, Milestone 7).
--
--   * menus.console: which console a menu belongs to. Existing rows are the 7 insights menus, so the
--     default 'INSIGHTS' needs no data update.
--   * four 'ADMIN' entries (Users, Roles, Menus, Protocols), all linked to SUPER_ADMIN. No other role
--     gets any; a SUPER_ADMIN assigns the Protocols tab (the only assignable one) from Role Detail.
--
-- Idempotent: safe to re-run against a database that already holds some of these rows.

ALTER TABLE menus ADD COLUMN IF NOT EXISTS console VARCHAR(20) NOT NULL DEFAULT 'INSIGHTS';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_menus_console') THEN
        ALTER TABLE menus ADD CONSTRAINT ck_menus_console CHECK (console IN ('INSIGHTS', 'ADMIN'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_menus_console ON menus (console);

INSERT INTO menus (menu_key, label, path, icon, sort_order, console) VALUES
    ('admin-users',     'Users',     '/users',     'UsersIcon',        1, 'ADMIN'),
    ('admin-roles',     'Roles',     '/roles',     'ShieldCheckIcon',  2, 'ADMIN'),
    ('admin-menus',     'Menus',     '/menus',     'Bars3Icon',        3, 'ADMIN'),
    ('admin-protocols', 'Protocols', '/protocols', 'DocumentTextIcon', 4, 'ADMIN')
ON CONFLICT (menu_key) DO NOTHING;

INSERT INTO role_menus (role_id, menu_id)
SELECT r.id, m.id
FROM roles r
JOIN menus m ON m.console = 'ADMIN'
WHERE r.name = 'SUPER_ADMIN'
ON CONFLICT (role_id, menu_id) DO NOTHING;
