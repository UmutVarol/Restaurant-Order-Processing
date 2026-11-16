-- How a business looks to its guests (QR menu, payment page, digital receipt): its own colour and logo instead of
-- Sofra's. One row per business, keyed by it; a business that never chose keeps Sofra's look (docs/DESIGN.md).
CREATE TABLE guest_branding
(
    id           BIGINT                   NOT NULL PRIMARY KEY,
    business_id  BIGINT                   NOT NULL REFERENCES businesses (id),
    accent       VARCHAR(7),
    logo         BYTEA,
    logo_type    VARCHAR(20),
    logo_version INTEGER                  NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP WITH TIME ZONE,
    updated_by   VARCHAR(50),
    version      BIGINT                   NOT NULL DEFAULT 0,
    CONSTRAINT uq_guest_branding_business UNIQUE (business_id),
    CONSTRAINT ck_guest_branding_accent CHECK (accent IS NULL OR accent ~ '^#[0-9a-f]{6}$'),
    CONSTRAINT ck_guest_branding_logo_type CHECK (logo_type IS NULL OR logo_type IN ('image/png', 'image/webp'))
);
