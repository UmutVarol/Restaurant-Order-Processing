-- Service settings were one row for the whole installation; now each business has its own, keyed by the business.
ALTER TABLE service_settings DROP CONSTRAINT ck_service_settings_single;
ALTER TABLE service_settings ALTER COLUMN id SET DATA TYPE BIGINT;
ALTER TABLE service_settings ADD CONSTRAINT uq_service_settings_business UNIQUE (business_id);
