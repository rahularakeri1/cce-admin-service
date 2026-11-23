-- cce-admin-service owns six tables in ccedb's public schema (shared with every
-- other CCE service). api_permissions is NOT created here — it already exists,
-- owned by gateway-service. See docs/data-dictionary.md and
-- docs/architecture-overview.md §9.1 for why.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username        VARCHAR(100)  NOT NULL,
    email           VARCHAR(255)  NOT NULL,
    first_name      VARCHAR(100)  NOT NULL,
    last_name       VARCHAR(100)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    facility_scope  VARCHAR(255),
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_status ON users (status);

CREATE TABLE roles (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100)  NOT NULL,
    display_name  VARCHAR(150)  NOT NULL,
    description   TEXT,
    is_system     BOOLEAN       NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE menus (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    menu_key    VARCHAR(100)  NOT NULL,
    label       VARCHAR(150)  NOT NULL,
    path        VARCHAR(255)  NOT NULL,
    icon        VARCHAR(100),
    sort_order  INTEGER       NOT NULL DEFAULT 0,
    is_active   BOOLEAN       NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_menus_menu_key UNIQUE (menu_key)
);

CREATE INDEX idx_menus_active ON menus (is_active);

CREATE TABLE role_menus (
    role_id     UUID        NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    menu_id     UUID        NOT NULL REFERENCES menus (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, menu_id)
);

CREATE TABLE user_roles (
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id     UUID        NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE admin_audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id    UUID         NOT NULL REFERENCES users (id),
    action      VARCHAR(50)  NOT NULL,
    entity_type VARCHAR(50)  NOT NULL,
    entity_id   VARCHAR(255) NOT NULL,
    before      JSONB,
    after       JSONB,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_audit_log_entity ON admin_audit_log (entity_type, entity_id);
CREATE INDEX idx_admin_audit_log_actor ON admin_audit_log (actor_id);
CREATE INDEX idx_admin_audit_log_created ON admin_audit_log (created_at);
