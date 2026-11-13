package com.gaterook.connector.api;

/**
 * An MCP resource of a connection, e.g. {@code mcpgw://connections/dev-db/schema}. Every URI a connection publishes is
 * under its own {@link #prefix}, or for an MCP App (which needs the {@code ui://} scheme) its {@link #appPrefix}: the
 * core routes a read by that alone, so one connection can never answer for another.
 */
public record ResourceDescriptor(String uri, String name, String mimeType, String description) {

  /** The MIME type of an MCP App's HTML. */
  public static final String APP_MIME_TYPE = "text/html;profile=mcp-app";

  /** {@code mcpgw://connections/<name>/}: where a connection's resources are. */
  public static String prefix(String connection) {
    return "mcpgw://connections/" + connection + "/";
  }

  /** {@code ui://gaterook/connections/<name>/}: where a connection's MCP Apps are. */
  public static String appPrefix(String connection) {
    return "ui://gaterook/connections/" + connection + "/";
  }
}
