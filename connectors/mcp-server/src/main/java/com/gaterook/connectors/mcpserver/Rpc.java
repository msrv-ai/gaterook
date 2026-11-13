package com.gaterook.connectors.mcpserver;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 as the upstream client writes and reads it: plain trees, never databind of records, so nothing a server
 * sends is lost on the way (a tool's {@code _meta}, a content block's annotations) and nothing needs reflection
 * metadata in the native image.
 */
final class Rpc {

  static final int PARSE_ERROR = -32700;
  static final int INVALID_REQUEST = -32600;
  static final int METHOD_NOT_FOUND = -32601;
  static final int INVALID_PARAMS = -32602;
  static final int INTERNAL_ERROR = -32603;
  /** 2026-07-28: the request's headers and body disagree. */
  static final int HEADER_MISMATCH = -32020;
  /** 2026-07-28: the request needs a client capability that wasn't declared. */
  static final int MISSING_CAPABILITY = -32021;
  /** 2026-07-28: the server doesn't speak the requested revision ({@code data.supported} names the ones it does). */
  static final int UNSUPPORTED_VERSION = -32022;

  private Rpc() {}

  static ObjectNode request(long id, String method, ObjectNode params) {
    ObjectNode m = JsonNodeFactory.instance.objectNode();
    m.put("jsonrpc", "2.0");
    m.put("id", id);
    m.put("method", method);
    m.set("params", params == null ? JsonNodeFactory.instance.objectNode() : params);
    return m;
  }

  static ObjectNode notification(String method, ObjectNode params) {
    ObjectNode m = JsonNodeFactory.instance.objectNode();
    m.put("jsonrpc", "2.0");
    m.put("method", method);
    if (params != null) {
      m.set("params", params);
    }
    return m;
  }

  static ObjectNode result(JsonNode id, ObjectNode result) {
    ObjectNode m = JsonNodeFactory.instance.objectNode();
    m.put("jsonrpc", "2.0");
    m.set("id", id);
    m.set("result", result);
    return m;
  }

  static ObjectNode error(JsonNode id, int code, String message) {
    ObjectNode m = JsonNodeFactory.instance.objectNode();
    m.put("jsonrpc", "2.0");
    m.set("id", id);
    m.putObject("error").put("code", code).put("message", message);
    return m;
  }

  static boolean isRequest(JsonNode m) {
    return m.path("method").isString() && m.has("id") && !m.get("id").isNull();
  }

  static boolean isNotification(JsonNode m) {
    return m.path("method").isString() && (!m.has("id") || m.get("id").isNull());
  }

  static boolean isResponse(JsonNode m) {
    return !m.has("method") && m.has("id") && (m.has("result") || m.has("error"));
  }

  /** The id of a request or response as the client keyed it, or {@code null} for one it never sent (a string id). */
  static Long id(JsonNode m) {
    JsonNode id = m.get("id");
    if (id != null && id.isIntegralNumber() && id.canConvertToLong()) {
      return id.asLong();
    }
    if (id != null && id.isString()) {
      try {
        return Long.parseLong(id.asString());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  /**
   * A JSON-RPC error the server answered with: data of the server, never something the gateway decided. The message
   * is cleaned and cut; {@code data} is kept as sent for the callers that read it ({@code supported} of {@code -32022}).
   */
  static final class Error extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int code;
    private final transient JsonNode data;

    Error(int code, String message, JsonNode data) {
      super(message, null, false, false);
      this.code = code;
      this.data = data;
    }

    static Error of(JsonNode error) {
      int code = error.path("code").asInt(INTERNAL_ERROR);
      String message = Texts.str(error.path("message"), "error " + code);
      JsonNode data = error.get("data");
      String text = message;
      if (data != null && !data.isNull()) {
        String d = data.isString() ? data.asString() : data.toString();
        if (!d.isBlank() && !message.contains(d)) {
          text = message + " (" + d + ")";
        }
      }
      return new Error(code, Texts.clean(text, 500, false), data);
    }

    int code() {
      return code;
    }

    JsonNode data() {
      return data;
    }
  }
}
