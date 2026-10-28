-- A till that queued an order while offline sends it with a key it made up; a retry with the same key returns the
-- order that was already placed instead of placing it twice.
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(80);
ALTER TABLE orders ADD CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key);
