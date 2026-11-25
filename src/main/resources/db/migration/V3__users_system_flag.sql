-- V3: marks the system-created accounts (the first admin from scripts/bootstrap-admin.sh).
--
-- A system account is read-only in the console: it cannot be deactivated, edited, or have its roles
-- changed — otherwise any other admin could lock the platform's own admin out. Mirrors roles.is_system
-- (V1), which already makes SUPER_ADMIN immutable.
--
-- Defaults to false for every existing and every console-created user; only the bootstrap script sets it,
-- so re-run scripts/bootstrap-admin.sh once to flag an already-bootstrapped admin.
ALTER TABLE users ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false;
