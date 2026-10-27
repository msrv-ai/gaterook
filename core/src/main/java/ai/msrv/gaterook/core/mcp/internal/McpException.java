package ai.msrv.gaterook.core.mcp.internal;

import tools.jackson.databind.JsonNode;

/** A protocol-level error of one request: becomes a JSON-RPC error with the era's HTTP status. */
public class McpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int code;
  private final transient JsonNode data;

  public McpException(int code, String message) {
    this(code, message, null);
  }

  public McpException(int code, String message, JsonNode data) {
    super(message, null, false, false);
    this.code = code;
    this.data = data;
  }

  public int code() {
    return code;
  }

  public JsonNode data() {
    return data;
  }

  public static McpException invalidParams(String message) {
    return new McpException(JsonRpc.INVALID_PARAMS, message);
  }

  /** The HTTP status of this error in {@code era} (report 01 §3.4). */
  public static int status(Era era, int code) {
    return switch (code) {
      case JsonRpc.PARSE_ERROR, JsonRpc.INVALID_REQUEST, JsonRpc.HEADER_MISMATCH, JsonRpc.MISSING_CAPABILITY,
          JsonRpc.UNSUPPORTED_VERSION -> 400;
      case JsonRpc.METHOD_NOT_FOUND -> era == Era.MODERN ? 404 : 200;
      case JsonRpc.INVALID_PARAMS -> era == Era.MODERN ? 400 : 200;
      default -> 200;
    };
  }
}
