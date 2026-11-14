-- Each business's own online payment account (Sofra Cloud): its iyzico merchant keys, sealed with AES-GCM
-- (docs/CLOUD.md). A restaurant's own server can still use the server-wide keys from its environment.
CREATE TABLE payment_settings
(
    id          BIGINT                   NOT NULL PRIMARY KEY,
    business_id BIGINT                   NOT NULL REFERENCES businesses (id),
    provider    VARCHAR(20)              NOT NULL,
    api_key     VARCHAR(600)             NOT NULL,
    secret_key  VARCHAR(600)             NOT NULL,
    key_hint    VARCHAR(8)               NOT NULL,
    sandbox     BOOLEAN                  NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by  VARCHAR(50)              NOT NULL,
    CONSTRAINT ck_payment_settings_provider CHECK (provider IN ('IYZICO')),
    CONSTRAINT uq_payment_settings_business UNIQUE (business_id)
);
