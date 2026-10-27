-- MCP Gateway database, schema version 1 (ARCHITECTURE.md §4).
--
-- V1 creates every table of every step at once, so that parallel steps never touch the same migration. A step that
-- still needs a column adds its own migration from its reserved range: M1 V1-V9, clients V20-V29, notifications
-- V30-V39, tunnel/OAuth V40-V49, plugins V50-V59, MCP servers V60-V69, templates V70-V79, audit/export V80-V89.
--
-- Column types are the ones Hibernate's `ddl-auto=validate` expects on SQLite (report 15 §9 rules 16-18): JSON is
-- CLOB (not TEXT), byte[] is BLOB, Instant is TIMESTAMP (sqlite-jdbc stores epoch milliseconds, which the audit
-- triggers compare against), @Version long is BIGINT, IDENTITY is INTEGER PRIMARY KEY AUTOINCREMENT. Hibernate's DDL
-- for SQLite creates no foreign keys, composite UNIQUE or CHECK constraints: they are written here by hand and are the
-- last line of defence behind the services.
--
-- `mac` columns: HMAC-SHA256 (key derived from the master keyset, info row-mac/v1) over the canonical JSON of the
-- security-relevant fields + id + version; a mismatch disables the object (ARCHITECTURE.md §4, "Целостность").

-- ─── Settings and policies ─────────────────────────────────────────────────────────────────────────────────────────

-- Keys: general, appearance, authorization, approval-rules, local-api, audit, backup, tunnel, oauth, core.instance-id,
-- catalog.sequence, catalog.last-good, activity.saved-views, onboarding.skipped, overview.getting-started-dismissed,
-- audit.head-mac. The value is any JSON document.
CREATE TABLE setting (
  key        VARCHAR(128) NOT NULL PRIMARY KEY,
  value      CLOB         NOT NULL,
  version    BIGINT       NOT NULL,
  updated_at TIMESTAMP    NOT NULL
);

-- sections: {sql, ssh, k8s, cloud, http, masking, limits, approvals: {timeout, comment, announceIn[]}}
CREATE TABLE policy (
  env        VARCHAR(8) NOT NULL PRIMARY KEY CHECK (env IN ('dev', 'stage', 'prod')),
  inherits   VARCHAR(8) CHECK (inherits IN ('dev', 'stage', 'prod')),
  sections   CLOB       NOT NULL,
  mac        BLOB,
  version    BIGINT     NOT NULL,
  updated_at TIMESTAMP  NOT NULL
);

-- ─── Secrets (report 12 §7.8) ──────────────────────────────────────────────────────────────────────────────────────

CREATE TABLE external_store (
  id              VARCHAR(36) NOT NULL PRIMARY KEY,
  kind            VARCHAR(8)  NOT NULL CHECK (kind IN ('vault', 'gcp-sm', 'aws-sm')),
  name            VARCHAR(64) NOT NULL UNIQUE,
  config          CLOB        NOT NULL,
  auth_secret_id  VARCHAR(36),
  state           VARCHAR(16) NOT NULL,
  last_checked_at TIMESTAMP,
  created_at      TIMESTAMP   NOT NULL,
  version         BIGINT      NOT NULL
);

-- field: JSON pointer of the secret in its owner's config. auth_secret_id above points here without a foreign key:
-- a store's sign-in secret is owned by the store (owner_kind 'store'), a cycle SQLite can't create in one pass.
CREATE TABLE secret (
  id                VARCHAR(36)   NOT NULL PRIMARY KEY,
  name              VARCHAR(128)  NOT NULL UNIQUE,
  kind              VARCHAR(12)   NOT NULL CHECK (kind IN ('local', 'reference', 'dynamic')),
  owner_kind        VARCHAR(16)   NOT NULL
                    CHECK (owner_kind IN ('connection', 'store', 'mcp-server', 'notification', 'tunnel', 'audit-export')),
  owner_id          VARCHAR(36)   NOT NULL,
  field             VARCHAR(256)  NOT NULL,
  ref_uri           VARCHAR(1024),
  store_id          VARCHAR(36)   REFERENCES external_store (id),
  current_version   INTEGER,
  remote_version    VARCHAR(64),
  remote_checked_at TIMESTAMP,
  rotated_at        TIMESTAMP,
  created_at        TIMESTAMP     NOT NULL,
  version           BIGINT        NOT NULL,
  UNIQUE (owner_kind, owner_id, field)
);

-- wrapped_dek: 144 bytes; key_id: the master keyset's primary key id at wrap time.
CREATE TABLE secret_version (
  id             VARCHAR(36)   NOT NULL PRIMARY KEY,
  secret_id      VARCHAR(36)   NOT NULL REFERENCES secret (id) ON DELETE CASCADE,
  secret_version INTEGER       NOT NULL,
  state          VARCHAR(12)   NOT NULL CHECK (state IN ('pending', 'current', 'previous', 'destroyed')),
  ciphertext     BLOB,
  wrapped_dek    BLOB,
  key_id         BIGINT,
  enc_format     INTEGER       NOT NULL,
  ref_uri        VARCHAR(1024),
  created_at     TIMESTAMP     NOT NULL,
  destroyed_at   TIMESTAMP,
  UNIQUE (secret_id, secret_version)
);
-- Re-wrap progress while the master key rotates.
CREATE INDEX secret_version_key ON secret_version (key_id) WHERE wrapped_dek IS NOT NULL;

CREATE TABLE secret_lease (
  lease_id   VARCHAR(256) NOT NULL PRIMARY KEY,
  store_id   VARCHAR(36)  NOT NULL REFERENCES external_store (id),
  secret_id  VARCHAR(36)  NOT NULL REFERENCES secret (id) ON DELETE CASCADE,
  issued_at  TIMESTAMP    NOT NULL,
  expires_at TIMESTAMP    NOT NULL,
  revoked_at TIMESTAMP
);

CREATE TABLE master_key_state (
  id                     INTEGER   NOT NULL PRIMARY KEY CHECK (id = 1),
  last_backup_at         TIMESTAMP,
  last_backup_key_ids    CLOB,
  rotation_target_key_id BIGINT,
  version                BIGINT    NOT NULL
);

-- ─── Connectors, connections, templates (reports 10 §7.6, 11, 16) ──────────────────────────────────────────────────

CREATE TABLE plugin (
  id                  VARCHAR(48)  NOT NULL PRIMARY KEY,
  current_version     VARCHAR(32)  NOT NULL,
  previous_version    VARCHAR(32),
  previous_kept_until TIMESTAMP,
  state               VARCHAR(16)  NOT NULL CHECK (state IN ('installed', 'disabled', 'revoked', 'failed')),
  signed              BOOLEAN      NOT NULL,
  source              VARCHAR(8)   NOT NULL CHECK (source IN ('file', 'url', 'catalog')),
  sha256              VARCHAR(64)  NOT NULL,
  size_bytes          BIGINT       NOT NULL,
  manifest            CLOB         NOT NULL,
  catalog_entry       VARCHAR(128),
  installed_at        TIMESTAMP    NOT NULL,
  updated_at          TIMESTAMP    NOT NULL,
  mac                 BLOB,
  version             BIGINT       NOT NULL
);

-- id: postgres, jira-service-desk, mcp-server, …
CREATE TABLE connector_type (
  id              VARCHAR(48) NOT NULL PRIMARY KEY,
  kind            VARCHAR(12) NOT NULL CHECK (kind IN ('BUILT_IN', 'PLUGIN', 'MCP_SERVER')),
  impl_version    VARCHAR(32) NOT NULL,
  manifest        CLOB        NOT NULL,
  manifest_sha256 VARCHAR(64) NOT NULL,
  enabled         BOOLEAN     NOT NULL,
  plugin_id       VARCHAR(48) REFERENCES plugin (id),
  updated_at      TIMESTAMP   NOT NULL,
  mac             BLOB,
  version         BIGINT      NOT NULL
);

-- config: non-secret settings; secrets appear only as {"$secret": "<id>"}. metadata: TestResult.metadataUpdate.
CREATE TABLE connection (
  id                VARCHAR(36)  NOT NULL PRIMARY KEY,
  name              VARCHAR(64)  NOT NULL UNIQUE,
  type              VARCHAR(48)  NOT NULL REFERENCES connector_type (id),
  env               VARCHAR(8)   NOT NULL CHECK (env IN ('dev', 'stage', 'prod')),
  config            CLOB         NOT NULL,
  metadata          CLOB,
  health_state      VARCHAR(12),
  health_latency_ms BIGINT,
  health_message    VARCHAR(512),
  health_checked_at TIMESTAMP,
  created_at        TIMESTAMP    NOT NULL,
  updated_at        TIMESTAMP    NOT NULL,
  mac               BLOB,
  version           BIGINT       NOT NULL
);
CREATE INDEX connection_type_env ON connection (type, env);

-- tools_snapshot: [{exposed, original, definitionHash, declared annotations}]
CREATE TABLE mcp_server (
  id               VARCHAR(36)  NOT NULL PRIMARY KEY,
  connection_id    VARCHAR(36)  NOT NULL UNIQUE REFERENCES connection (id) ON DELETE CASCADE,
  tool_prefix      VARCHAR(16)  NOT NULL UNIQUE CHECK (tool_prefix NOT GLOB '*[^a-z0-9_]*' AND tool_prefix GLOB '[a-z]*'),
  server_info      CLOB,
  protocol_version VARCHAR(16),
  tools_snapshot   CLOB         NOT NULL,
  snapshot_sha256  VARCHAR(64),
  npm_integrity    VARCHAR(160),
  catalog_entry    VARCHAR(128),
  reviewed_at      TIMESTAMP    NOT NULL,
  last_started_at  TIMESTAMP,
  state            VARCHAR(12)  NOT NULL,
  mac              BLOB,
  version          BIGINT       NOT NULL
);

-- connection_id is "Runs on"; NULL after the connection is deleted, then the status is 'invalid'.
CREATE TABLE template (
  id              VARCHAR(40)  NOT NULL PRIMARY KEY,
  kind            VARCHAR(16)  NOT NULL CHECK (kind IN ('sql-query', 'ssh-runbook', 'openapi')),
  name            VARCHAR(64)  NOT NULL,
  connection_id   VARCHAR(36)  REFERENCES connection (id) ON DELETE SET NULL,
  current_version INTEGER      NOT NULL,
  status          VARCHAR(12)  NOT NULL CHECK (status IN ('active', 'disabled', 'invalid')),
  catalog_ref     VARCHAR(160),
  created_at      TIMESTAMP    NOT NULL,
  updated_at      TIMESTAMP    NOT NULL,
  version         BIGINT       NOT NULL,
  UNIQUE (connection_id, kind, name)
);

-- Source documents (OpenAPI specs) by content hash, gzip-compressed.
CREATE TABLE template_blob (
  sha256     VARCHAR(64) NOT NULL PRIMARY KEY,
  media_type VARCHAR(64) NOT NULL,
  size_bytes BIGINT      NOT NULL,
  gz         BLOB        NOT NULL
);

-- Append-only (trigger below). natures: {"read": 9, "mutation": 5}.
CREATE TABLE template_version (
  id               VARCHAR(36)  NOT NULL PRIMARY KEY,
  template_id      VARCHAR(40)  NOT NULL REFERENCES template (id) ON DELETE CASCADE,
  template_version INTEGER      NOT NULL,
  definition       CLOB         NOT NULL,
  source_sha256    VARCHAR(64)  REFERENCES template_blob (sha256),
  digest           VARCHAR(64)  NOT NULL,
  natures          CLOB         NOT NULL,
  note             VARCHAR(500),
  created_at       TIMESTAMP    NOT NULL,
  mac              BLOB,
  UNIQUE (template_id, template_version)
);

CREATE VIEW runbook AS
  SELECT t.id, t.name, t.connection_id, v.template_version, v.definition, t.status
  FROM template t
  JOIN template_version v ON v.template_id = t.id AND v.template_version = t.current_version
  WHERE t.kind = 'ssh-runbook';

-- ─── Endpoints, tokens, clients (reports 01, 03) ───────────────────────────────────────────────────────────────────

-- env is derived (the strictest env of its connections; prod never mixes). description: list and ⌘K only, agents
-- don't see it. settings: protocol, limits, approvalWait (report 01 §4). risk_dismissed: {at, reason, ingredients}.
CREATE TABLE endpoint (
  id             VARCHAR(36)  NOT NULL PRIMARY KEY,
  slug           VARCHAR(63)  NOT NULL UNIQUE
                 CHECK (length(slug) BETWEEN 1 AND 63 AND slug NOT GLOB '*[^a-z0-9-]*' AND slug NOT GLOB '-*'),
  description    VARCHAR(500),
  env            VARCHAR(8)   NOT NULL CHECK (env IN ('dev', 'stage', 'prod')),
  access         VARCHAR(10)  NOT NULL CHECK (access IN ('standard', 'read-only')),
  enabled        BOOLEAN      NOT NULL DEFAULT 1,
  disabled_at    TIMESTAMP,
  instructions   CLOB,
  settings       CLOB         NOT NULL,
  published      BOOLEAN      NOT NULL,
  oauth_enabled  BOOLEAN      NOT NULL,
  risk_dismissed CLOB,
  created_at     TIMESTAMP    NOT NULL,
  updated_at     TIMESTAMP    NOT NULL,
  mac            BLOB,
  version        BIGINT       NOT NULL
);

CREATE TABLE endpoint_connection (
  id            VARCHAR(36) NOT NULL PRIMARY KEY,
  endpoint_id   VARCHAR(36) NOT NULL REFERENCES endpoint (id) ON DELETE CASCADE,
  connection_id VARCHAR(36) NOT NULL REFERENCES connection (id) ON DELETE CASCADE,
  added_at      TIMESTAMP   NOT NULL,
  mac           BLOB,
  UNIQUE (endpoint_id, connection_id)
);

-- connection_id NULL: endpoint-wide core tools. tool_key: sql_query | tpl:<id>:<opKey> | mcp:<connection>:<original> |
-- plugin:<type>:<tool>. overrides: stricter limits. ro_confirmation: {version, definitionHash, at}, external and plugin
-- tools only.
CREATE TABLE endpoint_tool (
  id              VARCHAR(36)  NOT NULL PRIMARY KEY,
  endpoint_id     VARCHAR(36)  NOT NULL REFERENCES endpoint (id) ON DELETE CASCADE,
  connection_id   VARCHAR(36)  REFERENCES connection (id) ON DELETE CASCADE,
  tool_key        VARCHAR(256) NOT NULL,
  mode            VARCHAR(10)  NOT NULL CHECK (mode IN ('OFF', 'READ_ONLY', 'APPROVAL')),
  overrides       CLOB,
  ro_confirmation CLOB,
  updated_at      TIMESTAMP    NOT NULL,
  mac             BLOB,
  version         BIGINT       NOT NULL
);
CREATE UNIQUE INDEX endpoint_tool_pair ON endpoint_tool (endpoint_id, connection_id, tool_key) WHERE connection_id IS NOT NULL;
CREATE UNIQUE INDEX endpoint_tool_wide ON endpoint_tool (endpoint_id, tool_key) WHERE connection_id IS NULL;

-- id: gr_…; label: codex-laptop.
CREATE TABLE client_grant (
  id            VARCHAR(40) NOT NULL PRIMARY KEY,
  client        VARCHAR(16) NOT NULL,
  endpoint_id   VARCHAR(36) NOT NULL REFERENCES endpoint (id) ON DELETE CASCADE,
  label         VARCHAR(40) NOT NULL CHECK (label NOT GLOB '*[^a-z0-9-]*'),
  created_at    TIMESTAMP   NOT NULL,
  revoked_at    TIMESTAMP,
  last_token_at TIMESTAMP,
  last_pid      BIGINT,
  mac           BLOB,
  version       BIGINT      NOT NULL
);

-- id: tok_…; prefix: gwt_K9xP (NULL for BRIDGE); hash: b64url(sha256). BRIDGE rows older than 24 h are purged.
CREATE TABLE api_token (
  id            VARCHAR(40) NOT NULL PRIMARY KEY,
  endpoint_id   VARCHAR(36) NOT NULL REFERENCES endpoint (id) ON DELETE CASCADE,
  kind          VARCHAR(8)  NOT NULL CHECK (kind IN ('NAMED', 'BRIDGE')),
  name          VARCHAR(40),
  prefix        VARCHAR(12),
  hash          VARCHAR(44) NOT NULL UNIQUE,
  grant_id      VARCHAR(40) REFERENCES client_grant (id) ON DELETE CASCADE,
  created_at    TIMESTAMP   NOT NULL,
  expires_at    TIMESTAMP   NOT NULL,
  last_used_at  TIMESTAMP,
  revoked_at    TIMESTAMP,
  revoke_reason VARCHAR(64),
  version       BIGINT      NOT NULL
);
CREATE INDEX api_token_endpoint ON api_token (endpoint_id, kind);

CREATE TABLE client_link (
  id             VARCHAR(36)   NOT NULL PRIMARY KEY,
  client         VARCHAR(16)   NOT NULL CHECK (client IN ('claude-desktop', 'codex', 'cursor', 'antigravity')),
  endpoint_id    VARCHAR(36)   NOT NULL REFERENCES endpoint (id) ON DELETE CASCADE,
  method         VARCHAR(16)   NOT NULL CHECK (method IN ('mcpb', 'config-bridge', 'helper', 'token', 'oauth')),
  config_path    VARCHAR(1024),
  entry_name     VARCHAR(48),
  grant_id       VARCHAR(40)   REFERENCES client_grant (id) ON DELETE SET NULL,
  token_id       VARCHAR(40)   REFERENCES api_token (id) ON DELETE SET NULL,
  written_sha256 VARCHAR(64),
  backup_path    VARCHAR(1024),
  status         VARCHAR(16)   NOT NULL,
  created_at     TIMESTAMP     NOT NULL,
  removed_at     TIMESTAMP,
  version        BIGINT        NOT NULL
);

-- ─── OAuth for claude.ai and local clients (report 02 §7.2) ────────────────────────────────────────────────────────

-- ingress: which authorization server issued the client (ARCHITECTURE.md §7.3).
CREATE TABLE oauth_client (
  id            VARCHAR(36)  NOT NULL PRIMARY KEY,
  kind          VARCHAR(4)   NOT NULL CHECK (kind IN ('CIMD', 'DCR')),
  ingress       VARCHAR(6)   NOT NULL CHECK (ingress IN ('LOCAL', 'TUNNEL')),
  client_id     VARCHAR(512) NOT NULL UNIQUE,
  client_name   VARCHAR(128),
  client_host   VARCHAR(253) NOT NULL,
  redirect_uris CLOB         NOT NULL,
  created_at    TIMESTAMP    NOT NULL,
  last_used_at  TIMESTAMP,
  mac           BLOB
);

-- id: og_…
CREATE TABLE oauth_grant (
  id              VARCHAR(40)  NOT NULL PRIMARY KEY,
  oauth_client_id VARCHAR(36)  NOT NULL REFERENCES oauth_client (id),
  endpoint_id     VARCHAR(36)  NOT NULL REFERENCES endpoint (id) ON DELETE CASCADE,
  ingress         VARCHAR(6)   NOT NULL CHECK (ingress IN ('LOCAL', 'TUNNEL')),
  resource        VARCHAR(512) NOT NULL,
  scope           VARCHAR(128) NOT NULL,
  state           VARCHAR(16)  NOT NULL CHECK (state IN ('active', 'address-changed', 'expired', 'revoked')),
  created_at      TIMESTAMP    NOT NULL,
  expires_at      TIMESTAMP    NOT NULL,
  revoked_at      TIMESTAMP,
  revoke_reason   VARCHAR(64),
  last_used_at    TIMESTAMP,
  version         BIGINT       NOT NULL
);

CREATE TABLE oauth_token (
  hash        VARCHAR(44) NOT NULL PRIMARY KEY,
  grant_id    VARCHAR(40) NOT NULL REFERENCES oauth_grant (id) ON DELETE CASCADE,
  kind        VARCHAR(8)  NOT NULL CHECK (kind IN ('ACCESS', 'REFRESH')),
  created_at  TIMESTAMP   NOT NULL,
  expires_at  TIMESTAMP   NOT NULL,
  consumed_at TIMESTAMP,
  replaced_by VARCHAR(44)
);

-- ─── Approvals (reports 01, 13) ────────────────────────────────────────────────────────────────────────────────────

-- id: ap_<12 chars>. endpoint_slug and connection_name are snapshots for the history. principal_id: named token id |
-- grant id | oauth_grant id | 'playground', never a rotating token. client_label: trusted label (token name, grant
-- label, OAuth client host). call_canonical: tool, connection, cfgVersion, arguments, template binding. action: the
-- ActionPreview. result_masked is kept for 60 minutes, then nulled. The status changes only by a versioned
-- compare-and-set (ARCHITECTURE.md §5 step 11); the entity is @DynamicUpdate.
CREATE TABLE approval (
  id                  VARCHAR(40)   NOT NULL PRIMARY KEY,
  call_id             VARCHAR(40)   NOT NULL,
  endpoint_id         VARCHAR(36)   REFERENCES endpoint (id) ON DELETE SET NULL,
  endpoint_slug       VARCHAR(63)   NOT NULL,
  connection_id       VARCHAR(36)   REFERENCES connection (id) ON DELETE SET NULL,
  connection_name     VARCHAR(64),
  tool                VARCHAR(128)  NOT NULL,
  tool_key            VARCHAR(256)  NOT NULL,
  env                 VARCHAR(8)    NOT NULL CHECK (env IN ('dev', 'stage', 'prod')),
  principal_kind      VARCHAR(12)   NOT NULL CHECK (principal_kind IN ('LOCAL_TOKEN', 'BRIDGE', 'OAUTH', 'PLAYGROUND')),
  principal_id        VARCHAR(40)   NOT NULL,
  client_label        VARCHAR(64),
  call_canonical      CLOB          NOT NULL,
  digest              VARCHAR(64)   NOT NULL,
  action              CLOB          NOT NULL,
  action_sha256       VARCHAR(64)   NOT NULL,
  prepared_state      CLOB,
  agent_note          VARCHAR(500),
  rule_id             VARCHAR(128)  NOT NULL,
  comment_policy      VARCHAR(12)   NOT NULL CHECK (comment_policy IN ('optional', 'recommended', 'required')),
  status              VARCHAR(12)   NOT NULL
                      CHECK (status IN ('pending', 'running', 'completed', 'rejected', 'expired', 'failed', 'cancelled')),
  comment             VARCHAR(2000),
  decided_via         VARCHAR(24),
  decided_external_id VARCHAR(64),
  decided_at          TIMESTAMP,
  expires_at          TIMESTAMP     NOT NULL,
  created_at          TIMESTAMP     NOT NULL,
  executed_at         TIMESTAMP,
  executed_sha256     VARCHAR(64),
  result_masked       CLOB,
  result_expires_at   TIMESTAMP,
  error               VARCHAR(1000),
  trace_context       VARCHAR(128),
  mac                 BLOB,
  version             BIGINT        NOT NULL
);
CREATE INDEX approval_pending ON approval (status, expires_at);
CREATE INDEX approval_dedup ON approval (endpoint_id, principal_id, digest) WHERE status = 'pending';
CREATE INDEX approval_created ON approval (created_at);

-- The feed of one request, inserts only: requested | notified | delivered | decided | running | completed | failed |
-- expired. Notifications append here and never write the approval row.
CREATE TABLE approval_event (
  id          INTEGER     PRIMARY KEY AUTOINCREMENT,
  approval_id VARCHAR(40) NOT NULL REFERENCES approval (id) ON DELETE CASCADE,
  at          TIMESTAMP   NOT NULL,
  kind        VARCHAR(16) NOT NULL,
  detail      CLOB
);
CREATE INDEX approval_event_ap ON approval_event (approval_id, id);

-- ─── Audit, append-only (reports 01 §6.7, 14 §6.5, 15 rule 21) ─────────────────────────────────────────────────────

-- Phase 'received' with the decision is written BEFORE execute (fail-closed); 'resolved' and 'completed' follow.
-- hour_bucket: epoch hours, for histograms without date functions. client: codex | claude-desktop | claude-ai | cursor |
-- antigravity | inspector | playground | other; client_info is what the client reports about itself. token_label:
-- codex-laptop | gwt_K9xP… | og_…. result_preview: masked, ≤ 4 KB. facets: runbook, template, k8s.*.
-- prev_sha256/row_sha256: the hash chain, its head is MACed into setting 'audit.head-mac'.
CREATE TABLE tool_call (
  id                INTEGER       PRIMARY KEY AUTOINCREMENT,
  call_id           VARCHAR(40)   NOT NULL,
  phase             VARCHAR(10)   NOT NULL CHECK (phase IN ('received', 'resolved', 'completed')),
  created_at        TIMESTAMP     NOT NULL,
  hour_bucket       BIGINT        NOT NULL,
  endpoint_slug     VARCHAR(63)   NOT NULL,
  tool_name         VARCHAR(128)  NOT NULL,
  tool_key          VARCHAR(256),
  connection_name   VARCHAR(64),
  env               VARCHAR(8)    CHECK (env IN ('dev', 'stage', 'prod')),
  client            VARCHAR(32),
  client_info       CLOB,
  token_label       VARCHAR(64),
  principal_kind    VARCHAR(12)   CHECK (principal_kind IN ('LOCAL_TOKEN', 'BRIDGE', 'OAUTH', 'PLAYGROUND')),
  ingress           VARCHAR(10)   CHECK (ingress IN ('LOCAL', 'PLAYGROUND', 'TUNNEL')),
  caller_ip         VARCHAR(45),
  protocol_version  VARCHAR(10),
  approval_delivery VARCHAR(8)    CHECK (approval_delivery IN ('mrtr', 'handle', 'task')),
  trace_id          VARCHAR(32),
  span_id           VARCHAR(16),
  args_masked       CLOB,
  args_sha256       VARCHAR(64),
  classification    VARCHAR(10)   CHECK (classification IN ('READ', 'MUTATION', 'DENY')),
  decision          VARCHAR(10)   NOT NULL
                    CHECK (decision IN ('allowed', 'approved', 'pending', 'denied', 'rejected', 'expired', 'error')),
  rule_id           VARCHAR(128),
  approval_id       VARCHAR(40),
  duration_ms       BIGINT,
  gateway_ms        BIGINT,
  resource_ms       BIGINT,
  approval_wait_ms  BIGINT,
  result_bytes      BIGINT,
  result_sha256     VARCHAR(64),
  result_preview    CLOB,
  masked_count      INTEGER,
  truncated         BOOLEAN,
  error_kind        VARCHAR(24),
  error_message     VARCHAR(1000),
  facets            CLOB,
  action_sha256     VARCHAR(64),
  executed_sha256   VARCHAR(64),
  outcome_unknown   BOOLEAN,
  decided_via       VARCHAR(24),
  comment_sha256    VARCHAR(64),
  prev_sha256       VARCHAR(64)   NOT NULL,
  row_sha256        VARCHAR(64)   NOT NULL
);
CREATE INDEX tool_call_time ON tool_call (created_at);
CREATE INDEX tool_call_call ON tool_call (call_id);
CREATE INDEX tool_call_bucket ON tool_call (hour_bucket, decision);
CREATE INDEX tool_call_trace ON tool_call (trace_id);
CREATE INDEX tool_call_tool ON tool_call (tool_name, created_at);
CREATE INDEX tool_call_conn ON tool_call (connection_name, created_at);
CREATE INDEX tool_call_ep ON tool_call (endpoint_slug, created_at);
CREATE INDEX tool_call_token ON tool_call (token_label, created_at);

-- Activity › Setup changes. by_actor is always 'you' on the desktop (decision 1).
CREATE TABLE setup_change (
  id              INTEGER       PRIMARY KEY AUTOINCREMENT,
  created_at      TIMESTAMP     NOT NULL,
  what            VARCHAR(24)   NOT NULL CHECK (what IN ('tunnel', 'mode', 'token', 'grant', 'risk-warning', 'plugin',
                    'mcp-server', 'connection', 'secret', 'policy', 'settings', 'client', 'oauth', 'host-key')),
  subject         VARCHAR(256)  NOT NULL,
  change_from     CLOB,
  change_to       CLOB,
  reason          VARCHAR(1000),
  by_actor        VARCHAR(16)   NOT NULL,
  env             VARCHAR(8)    CHECK (env IN ('dev', 'stage', 'prod')),
  endpoint_slug   VARCHAR(63),
  connection_name VARCHAR(64),
  details         CLOB,
  prev_sha256     VARCHAR(64)   NOT NULL,
  row_sha256      VARCHAR(64)   NOT NULL
);
CREATE INDEX setup_change_time ON setup_change (created_at);

-- title: masked and always "unverified"; summary: phases, decisions, waitedForPeopleMs (TraceSummarizer).
CREATE TABLE trace (
  trace_id      VARCHAR(32)  NOT NULL PRIMARY KEY,
  title         VARCHAR(120),
  title_source  VARCHAR(8)   CHECK (title_source IN ('meta', 'note', 'client')),
  first_at      TIMESTAMP    NOT NULL,
  last_at       TIMESTAMP    NOT NULL,
  client        VARCHAR(32),
  endpoint_slug VARCHAR(63),
  calls         INTEGER      NOT NULL,
  summary       CLOB,
  version       BIGINT       NOT NULL
);
CREATE INDEX trace_last ON trace (last_at);

-- The audit is append-only at the database level too, and kept at least one year whatever the code does. The
-- retention job deletes older rows only (created_at is epoch milliseconds, see the header).
CREATE TRIGGER tool_call_no_update BEFORE UPDATE ON tool_call
BEGIN
  SELECT RAISE(ABORT, 'audit is append-only');
END;

CREATE TRIGGER tool_call_no_early_delete BEFORE DELETE ON tool_call
WHEN OLD.created_at > (unixepoch('now', '-365 days') * 1000)
BEGIN
  SELECT RAISE(ABORT, 'audit is kept at least 1 year');
END;

CREATE TRIGGER setup_change_no_update BEFORE UPDATE ON setup_change
BEGIN
  SELECT RAISE(ABORT, 'audit is append-only');
END;

CREATE TRIGGER setup_change_no_early_delete BEFORE DELETE ON setup_change
WHEN OLD.created_at > (unixepoch('now', '-365 days') * 1000)
BEGIN
  SELECT RAISE(ABORT, 'audit is kept at least 1 year');
END;

CREATE TRIGGER template_version_no_update BEFORE UPDATE ON template_version
BEGIN
  SELECT RAISE(ABORT, 'template versions are append-only');
END;

-- schedule: stream | daily@02:00 | hourly. Rows up to last_exported_id are exported; retention never deletes past it.
CREATE TABLE audit_export (
  id               VARCHAR(36)   NOT NULL PRIMARY KEY,
  kind             VARCHAR(8)    NOT NULL CHECK (kind IN ('siem', 's3', 'bigquery')),
  name             VARCHAR(64)   NOT NULL UNIQUE,
  enabled          BOOLEAN       NOT NULL,
  config           CLOB          NOT NULL,
  schedule         VARCHAR(32)   NOT NULL,
  secret_id        VARCHAR(36),
  last_exported_id BIGINT,
  last_exported_at TIMESTAMP,
  state            VARCHAR(16),
  failing_since    TIMESTAMP,
  last_attempt_at  TIMESTAMP,
  last_error       VARCHAR(1000),
  version          BIGINT        NOT NULL
);

-- ─── Notifications (report 13 §7) ──────────────────────────────────────────────────────────────────────────────────

-- config: deliverTo, channelId, protectContent…; secrets only as {"$secret": "<id>"}.
CREATE TABLE notification_channel (
  kind             VARCHAR(8)   NOT NULL PRIMARY KEY CHECK (kind IN ('desktop', 'slack', 'telegram')),
  enabled          BOOLEAN      NOT NULL,
  config           CLOB         NOT NULL,
  state            VARCHAR(16),
  state_detail     VARCHAR(512),
  last_delivery_at TIMESTAMP,
  updated_at       TIMESTAMP    NOT NULL,
  version          BIGINT       NOT NULL
);

CREATE TABLE notify_link (
  id         VARCHAR(36)  NOT NULL PRIMARY KEY,
  channel    VARCHAR(8)   NOT NULL CHECK (channel IN ('slack', 'telegram')),
  team_id    VARCHAR(32),
  user_id    VARCHAR(64)  NOT NULL,
  chat_id    VARCHAR(32),
  display    VARCHAR(128),
  linked_at  TIMESTAMP    NOT NULL,
  revoked_at TIMESTAMP
);

CREATE TABLE notify_delivery (
  id              VARCHAR(36) NOT NULL PRIMARY KEY,
  approval_id     VARCHAR(40) NOT NULL REFERENCES approval (id) ON DELETE CASCADE,
  channel         VARCHAR(8)  NOT NULL CHECK (channel IN ('desktop', 'slack', 'telegram')),
  target          VARCHAR(64) NOT NULL,
  external_ref    VARCHAR(64),
  offered_approve BOOLEAN     NOT NULL,
  state           VARCHAR(10) NOT NULL CHECK (state IN ('sending', 'sent', 'resolved', 'failed')),
  error           VARCHAR(512),
  sent_at         TIMESTAMP,
  resolved_at     TIMESTAMP,
  version         BIGINT      NOT NULL
);
CREATE INDEX notify_delivery_approval ON notify_delivery (approval_id);

-- code_hash: b64url(sha256) of the pairing code; candidate: the chat or user that sent /start <code>.
CREATE TABLE notify_pairing (
  code_hash   VARCHAR(44) NOT NULL PRIMARY KEY,
  channel     VARCHAR(8)  NOT NULL CHECK (channel IN ('slack', 'telegram')),
  expires_at  TIMESTAMP   NOT NULL,
  consumed_at TIMESTAMP,
  candidate   CLOB
);
