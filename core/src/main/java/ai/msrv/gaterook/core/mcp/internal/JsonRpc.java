package ai.msrv.gaterook.core.mcp.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 codes the gateway answers with (report 01 §3.4) and the error object. {@code -32000…-32019} are never
 * used (implementation-defined in the legacy range), {@code -32020…-32099} only as the 2026-07-28 spec defines them.
 */
public final class JsonRpc {

  public static final int PARSE_ERROR = -32700;
  public static final int INVALID_REQUEST = -32600;
  public static final int METHOD_NOT_FOUND = -32601;
  public static final int INVALID_PARAMS = -32602;
  public static final int INTERNAL_ERROR = -32603;
  /** 2025-11-25: a resource that doesn't exist (2026-07-28 uses {@link #INVALID_PARAMS}). */
  public static final int RESOURCE_NOT_FOUND = -32002;
  public static final int HEADER_MISMATCH = -32020;
  public static final int MISSING_CAPABILITY = -32021;
  public static final int UNSUPPORTED_VERSION = -32022;

  private JsonRpc() {}

  public static ObjectNode error(JsonNode id, int code, String message, JsonNode data) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("jsonrpc", "2.0");
    if (id == null) {
      n.putNull("id");
    } else {
      n.set("id", id);
    }
    ObjectNode e = n.putObject("error");
    e.put("code", code);
    e.put("message", message);
    if (data != null) {
      e.set("data", data);
    }
    return n;
  }

  public static ObjectNode result(JsonNode id, ObjectNode result) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("jsonrpc", "2.0");
    n.set("id", id);
    n.set("result", result);
    return n;
  }
}
