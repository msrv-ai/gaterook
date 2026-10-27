-- Add an explicit per-tool Bypass mode. Rebuild the leaf table to extend its CHECK constraint without changing
-- existing rows, optimistic versions, signatures, or the uniqueness of connection pairs and endpoint-wide tools.
CREATE TABLE endpoint_tool_next (
  id              VARCHAR(36)  NOT NULL PRIMARY KEY,
  endpoint_id     VARCHAR(36)  NOT NULL REFERENCES endpoint (id) ON DELETE CASCADE,
  connection_id   VARCHAR(36)  REFERENCES connection (id) ON DELETE CASCADE,
  tool_key        VARCHAR(256) NOT NULL,
  mode            VARCHAR(10)  NOT NULL CHECK (mode IN ('OFF', 'READ_ONLY', 'APPROVAL', 'BYPASS')),
  overrides       CLOB,
  ro_confirmation CLOB,
  updated_at      TIMESTAMP    NOT NULL,
  mac             BLOB,
  version         BIGINT       NOT NULL
);
INSERT INTO endpoint_tool_next (id, endpoint_id, connection_id, tool_key, mode, overrides, ro_confirmation,
                               updated_at, mac, version)
SELECT id, endpoint_id, connection_id, tool_key, mode, overrides, ro_confirmation, updated_at, mac, version
FROM endpoint_tool;
DROP TABLE endpoint_tool;
ALTER TABLE endpoint_tool_next RENAME TO endpoint_tool;
CREATE UNIQUE INDEX endpoint_tool_pair ON endpoint_tool (endpoint_id, connection_id, tool_key) WHERE connection_id IS NOT NULL;
CREATE UNIQUE INDEX endpoint_tool_wide ON endpoint_tool (endpoint_id, tool_key) WHERE connection_id IS NULL;
