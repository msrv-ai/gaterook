package ai.msrv.gaterook.core.mcpservers.internal;

import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerEntity;
import ai.msrv.gaterook.core.mcpservers.internal.persistence.McpServerRepository;
import ai.msrv.gaterook.core.platform.RowMac;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The MAC of {@code mcp_server} rows (ARCHITECTURE.md §4): the reviewed snapshot decides what an endpoint publishes
 * and which definition a call may run against, and the npm integrity decides which code starts, so a row edited
 * outside the core serves nothing until the person reviews it. When it last started is not part of it.
 */
@Component
public class McpServerRows {

  private static final String TABLE = "mcp_server";

  private final RowMac rowMac;
  private final McpServerRepository repository;

  public McpServerRows(RowMac rowMac, McpServerRepository repository) {
    this.rowMac = rowMac;
    this.repository = repository;
  }

  /** Signs a row after the flush that settled its version (the MAC column doesn't bump it). */
  public void sign(McpServerEntity row) {
    row.setMac(rowMac.sign(TABLE, row.getId(), row.getVersion(), fields(row)));
    repository.saveAndFlush(row);
  }

  public boolean verify(McpServerEntity row) {
    return rowMac.verify(TABLE, row.getId(), row.getVersion(), fields(row), row.getMac());
  }

  /** A JSON column as the tree it reads back as. */
  public JsonNode tree(Object column) {
    return rowMac.tree(column);
  }

  private JsonNode fields(McpServerEntity row) {
    ObjectNode f = rowMac.fields();
    f.put("connectionId", row.getConnectionId());
    f.put("toolPrefix", row.getToolPrefix());
    f.set("serverInfo", rowMac.tree(row.getServerInfo()));
    f.put("protocolVersion", row.getProtocolVersion());
    f.set("tools", rowMac.tree(row.getToolsSnapshot()));
    f.put("snapshotSha256", row.getSnapshotSha256());
    f.put("npmIntegrity", row.getNpmIntegrity());
    f.put("catalogEntry", row.getCatalogEntry());
    f.put("state", row.getState());
    return f;
  }
}
