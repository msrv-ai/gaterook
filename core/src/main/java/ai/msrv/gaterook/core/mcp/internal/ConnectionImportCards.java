package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.platform.Ids;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Static navigation-only MCP App. The caller-owned session remains authoritative in the import service. */
public final class ConnectionImportCards {
  public static final String URI = "ui://gaterook/connection-import-card/v1.html";

  private ConnectionImportCards() {}

  public static ObjectNode descriptor() {
    return JsonNodeFactory.instance.objectNode().put("uri", URI).put("name", "GateRook connection import")
        .put("description", "Open a submitted connection import for review in GateRook.")
        .put("mimeType", McpAppResource.MIME_TYPE);
  }

  public static ObjectNode read() {
    return McpAppResource.read(URI, Template.HTML);
  }

  /** Explicit projection: never copy file contents, paths, credential sources, capabilities or error details. */
  public static ToolResult result(ObjectNode summary) {
    String id = summary.path("sessionId").asString();
    String status = summary.path("status").asString();
    int count = summary.path("count").asInt(-1);
    if (!Ids.matches("ci_", id) || !("review".equals(status) || "imported".equals(status))
        || !summary.path("count").isInt() || count < 0 || count > 50) {
      throw new IllegalArgumentException("Invalid connection import card summary");
    }
    String url = "gaterook://connection-import/" + id;
    ObjectNode data = JsonNodeFactory.instance.objectNode().put("kind", "gaterook.connection-import-card")
        .put("version", 1).put("sessionId", id).put("status", status).put("count", count)
        .put("reviewUrl", url).put("canOpen", "review".equals(status));
    String text = "review".equals(status)
        ? count + (count == 1 ? " connection candidate is" : " connection candidates are")
            + " ready for review. Nothing has been imported. "
        : "The selected connections have been imported. ";
    return ToolResult.json(data, text + "[Open import in GateRook](" + url + ").");
  }

  private static final class Template {
    private static final String HTML = McpAppResource.assemble("connection-import-card");
  }
}
