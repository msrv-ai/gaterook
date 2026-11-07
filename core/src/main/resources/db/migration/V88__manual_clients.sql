-- Manually configured AI apps retain their display name and the token that identifies their own connection.
-- Token values remain exclusively in api_token as hashes; this table contains no credentials.
CREATE TABLE manual_client (
    id VARCHAR(36) PRIMARY KEY NOT NULL,
    name VARCHAR(80) NOT NULL,
    endpoint_id VARCHAR(36) NOT NULL REFERENCES endpoint(id) ON DELETE CASCADE,
    token_id VARCHAR(40) NOT NULL REFERENCES api_token(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX manual_client_endpoint ON manual_client(endpoint_id);
