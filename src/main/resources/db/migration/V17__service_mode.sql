-- How the restaurant takes orders: the waiter (CLASSIC), the guest's phone with a waiter for what the rules hold
-- back (MIXED), or the guest's phone for everything but the dishes that always need a waiter (QR_FIRST).

-- one row: the restaurant's service settings
CREATE TABLE service_settings
(
    id                     SMALLINT    NOT NULL PRIMARY KEY,
    mode                   VARCHAR(20) NOT NULL,
    -- MIXED: a cart above this total waits for a waiter; no value means no limit
    auto_accept_limit      NUMERIC(12, 2),
    -- MIXED: the first cart of a table nobody is seated at waits for a waiter, who sees the guests are really there
    first_cart_needs_staff BOOLEAN     NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE,
    updated_by             VARCHAR(50),
    version                BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_service_settings_single CHECK (id = 1),
    CONSTRAINT ck_service_settings_mode CHECK (mode IN ('CLASSIC', 'MIXED', 'QR_FIRST')),
    CONSTRAINT ck_service_settings_limit CHECK (auto_accept_limit IS NULL OR auto_accept_limit >= 0)
);

INSERT INTO service_settings (id, mode, auto_accept_limit, first_cart_needs_staff)
VALUES (1, 'MIXED', 1500.00, TRUE);

-- dishes a waiter always confirms when a guest orders them from the table, such as alcohol
ALTER TABLE menu_items ADD COLUMN needs_staff_approval BOOLEAN NOT NULL DEFAULT FALSE;

-- why a cart waits for a waiter, and whether one went to the kitchen by the rules alone
ALTER TABLE guest_requests ADD COLUMN hold_reason VARCHAR(20);
ALTER TABLE guest_requests ADD COLUMN auto_accepted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE guest_requests ADD CONSTRAINT ck_guest_requests_hold_reason
    CHECK (hold_reason IS NULL OR hold_reason IN ('CLASSIC_MODE', 'NEEDS_APPROVAL', 'FIRST_CART', 'OVER_LIMIT', 'BURST'));

CREATE INDEX ix_guest_requests_auto ON guest_requests (table_id, auto_accepted, created_at);
