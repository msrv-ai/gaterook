-- ARCHITECTURE.md §7.5: the definition of an external MCP server's tool (the SHA-256 of what the endpoint publishes,
-- ToolPairs.definitionHash) that its mode on this endpoint was set or reviewed against. A tool whose definition differs
-- from it does not keep Read only or Bypass. NULL where nothing is recorded: built-in and plugin tools, and rows from
-- before this column (the start that applies this migration records the definitions it holds for them, once). The row
-- MAC covers the value only when it is set, so rows written before this column keep verifying.
ALTER TABLE endpoint_tool ADD COLUMN reviewed_definition VARCHAR(64);
