-- Safeguards for online payments: fraud review, safe refund retries and per-link checkout limits.

-- The provider charged the card but its fraud team is still checking the payment.
ALTER TABLE payments ADD COLUMN under_review BOOLEAN DEFAULT FALSE NOT NULL;

-- A refund being sent to the provider is claimed until this time, so two servers (or a retry and the first
-- attempt) never send the same refund twice.
ALTER TABLE refunds ADD COLUMN claimed_until TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_payments_link_status ON payments (payment_link_id, status);
