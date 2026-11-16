-- F1: when a table's waiting ring turns amber ("look") and red ("long"), in minutes; set once for the business
-- instead of on every device. Both 0 switches the rings off.
ALTER TABLE service_settings ADD COLUMN wait_look_minutes INT NOT NULL DEFAULT 12;
ALTER TABLE service_settings ADD COLUMN wait_long_minutes INT NOT NULL DEFAULT 20;
ALTER TABLE service_settings ADD CONSTRAINT ck_service_settings_wait
    CHECK ((wait_look_minutes = 0 AND wait_long_minutes = 0)
        OR (wait_look_minutes >= 1 AND wait_long_minutes > wait_look_minutes AND wait_long_minutes <= 120));
