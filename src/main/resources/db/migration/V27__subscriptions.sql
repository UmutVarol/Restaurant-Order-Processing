-- Sofra Cloud subscriptions (docs/CLOUD.md): who collects, which subscription, what is billed, and since when a
-- payment is late. A restaurant's own server never fills these: business 1 pays for itself.
ALTER TABLE businesses ADD COLUMN owner_email VARCHAR(120);
ALTER TABLE businesses ADD COLUMN billing_provider VARCHAR(20);
ALTER TABLE businesses ADD COLUMN billing_customer_ref VARCHAR(100);
ALTER TABLE businesses ADD COLUMN billing_subscription_ref VARCHAR(100);
ALTER TABLE businesses ADD COLUMN billing_interval VARCHAR(10);
ALTER TABLE businesses ADD COLUMN billed_branches INTEGER;
ALTER TABLE businesses ADD COLUMN period_ends_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE businesses ADD COLUMN past_due_since TIMESTAMP WITH TIME ZONE;
-- providers may deliver webhooks out of order: an event older than the last one applied is ignored
ALTER TABLE businesses ADD COLUMN billing_event_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE businesses ADD CONSTRAINT ck_businesses_billing_interval CHECK (billing_interval IN ('MONTHLY', 'YEARLY'));
ALTER TABLE businesses ADD CONSTRAINT uq_businesses_subscription UNIQUE (billing_provider, billing_subscription_ref);
