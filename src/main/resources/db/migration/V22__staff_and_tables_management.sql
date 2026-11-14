-- Managers add and remove staff and tables themselves.

-- Each token carries the account's session version; raising it ends every session at once. Raised when an account
-- is switched off, its role changes or its password is reset or changed, so the change takes effect immediately
-- rather than when the token expires.
ALTER TABLE app_users ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0;
-- A password someone else chose (the installer, a manager's reset) must be replaced at the next sign-in.
ALTER TABLE app_users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_users ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;

-- A removed table keeps its history (orders, bills, reservations point at it); it just leaves the floor.
ALTER TABLE dining_tables ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
