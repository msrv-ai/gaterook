package com.gaterook.connector.api;

import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Neutral result, no MCP types.
 *
 * <p>The core masks it, cuts it (whole rows or entries), renders text for TABLE and LOGS, fills
 * {@code maskedCount}/{@code resultBytes}, writes the audit and then serializes it for the call's revision.
 * {@code isError=false} for any answer of the resource (HTTP 4xx, exit code 3): that is data.
 *
 * @param truncatedReason row_limit | size_limit | output_limit | timeout | partial | head
 * @param maskPaths       known-sensitive JSON paths
 * @param maskedCount     filled by the core
 * @param resultBytes     filled by the core
 */
public record ToolResult(List<Content> content, JsonNode structuredContent, boolean isError, Table table,
    boolean truncated, String truncatedReason, List<String> maskPaths, int maskedCount, long resultBytes,
    JsonNode meta) {

  /**
   * One block of the result.
   *
   * @param type {@code text} | {@code resource_link} | {@code image} | {@code audio} | {@code resource} (an embedded
   *             resource: its {@code uri}, and either its {@code text} or a blob in {@code data})
   * @param data Base64 of an image, audio or blob, or {@code null}: the core withholds a block whose bytes carry a
   *             secret and one larger than the call's limit, never changes it
   * @param meta the block's {@code _meta} as the resource gave it (an MCP App's {@code ui}: its CSP), or {@code null}
   */
  public record Content(String type, String text, String uri, String mimeType, String name, String data,
      JsonNode meta) {

    public Content(String type, String text, String uri, String mimeType, String name) {
      this(type, text, uri, mimeType, name, null, null);
    }

    public static Content text(String text) {
      return new Content("text", text, null, null, null);
    }

    public static Content image(String base64, String mimeType) {
      return new Content("image", null, null, mimeType, null, base64, null);
    }

    public static Content audio(String base64, String mimeType) {
      return new Content("audio", null, null, mimeType, null, base64, null);
    }

    /** An embedded resource: {@code text}, or a Base64 {@code blob}. */
    public static Content resource(String uri, String mimeType, String text, String blob) {
      return new Content("resource", text, uri, mimeType, null, blob, null);
    }

    public static Content link(String uri, String name, String mimeType) {
      return new Content("resource_link", null, uri, mimeType, name, null, null);
    }

    public Content withMeta(JsonNode value) {
      return new Content(type, text, uri, mimeType, name, data, value);
    }

    /** Whether the block carries bytes rather than text: an image, audio, a blob. */
    public boolean binary() {
      return data != null;
    }
  }

  public record Column(String name, String type, String sourceSchema, String sourceTable, String sourceColumn) {}

  public record Table(List<Column> columns, ArrayNode rows, String nextCursor) {}

  public static ToolResult text(String t) {
    return new ToolResult(List.of(Content.text(t)), null, false, null, false, null, List.of(), 0, 0, null);
  }

  public static ToolResult json(JsonNode s, String t) {
    return new ToolResult(t == null ? List.of() : List.of(Content.text(t)), s, false, null, false, null, List.of(),
        0, 0, null);
  }

  public static ToolResult table(Table t, JsonNode s) {
    return new ToolResult(List.of(), s, false, t, false, null, List.of(), 0, 0, null);
  }

  public static ToolResult error(String m) {
    return new ToolResult(List.of(Content.text(m)), null, true, null, false, null, List.of(), 0, 0, null);
  }

  public ToolResult truncated(String reason) {
    return new ToolResult(content, structuredContent, isError, table, true, reason, maskPaths, maskedCount,
        resultBytes, meta);
  }

  public ToolResult maskPaths(List<String> p) {
    return new ToolResult(content, structuredContent, isError, table, truncated, truncatedReason, List.copyOf(p),
        maskedCount, resultBytes, meta);
  }

  public ToolResult withMeta(JsonNode m) {
    return new ToolResult(content, structuredContent, isError, table, truncated, truncatedReason, maskPaths,
        maskedCount, resultBytes, m);
  }

  /** Core only: the masked and cut result with its accounting. */
  public ToolResult accounted(List<Content> c, JsonNode s, Table t, int masked, long bytes) {
    return new ToolResult(c, s, isError, t, truncated, truncatedReason, maskPaths, masked, bytes, meta);
  }
}
