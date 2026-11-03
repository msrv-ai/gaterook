package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * An MCP server's {@code tools/call} result as the core's neutral {@link ToolResult} (report 11 §3.1 "Result",
 * ARCHITECTURE.md §7.5): text blocks pass as they are, links and embedded resources become text with their URI —
 * data, never something a client would fetch on the agent's behalf — and binary content (images, audio, blobs) is
 * replaced by a note with its type and size. {@code isError} of the server stays; the core masks and cuts the result
 * like any other.
 */
final class Results {

  private Results() {}

  static ToolResult of(JsonNode r) {
    List<ToolResult.Content> content = new ArrayList<>();
    for (JsonNode c : r.path("content")) {
      content.add(ToolResult.Content.text(text(c)));
    }
    JsonNode s = r.get("structuredContent");
    JsonNode structured = s == null || s.isNull() ? null : s;
    boolean error = r.path("isError").asBoolean(false);
    return new ToolResult(List.copyOf(content), structured, error, null, false, null, List.of(), 0, 0, null);
  }

  /** The text a content block becomes. */
  static String text(JsonNode c) {
    String type = Texts.str(c.get("type"), "");
    return switch (type) {
      case "text" -> Texts.str(c.get("text"), "");
      case "image" -> "[image " + Texts.str(c.get("mimeType"), "") + ", " + size(Texts.str(c.get("data"), null))
          + ", not passed on by GateRook]";
      case "audio" -> "[audio " + Texts.str(c.get("mimeType"), "") + ", " + size(Texts.str(c.get("data"), null))
          + ", not passed on by GateRook]";
      case "resource_link" -> "Resource link (data, not fetched by GateRook): " + Texts.str(c.get("uri"), "")
          + (c.path("name").isString() ? " · " + c.get("name").asString() : "")
          + (c.path("mimeType").isString() ? " · " + c.get("mimeType").asString() : "");
      case "resource" -> embedded(c.path("resource"));
      default -> "[" + Texts.clean(type.isEmpty() ? "unknown" : type, 40, false) + " content, not passed on by "
          + "GateRook]";
    };
  }

  private static String embedded(JsonNode r) {
    if (r.path("text").isString()) {
      return "Resource " + Texts.str(r.get("uri"), "") + ":\n" + r.get("text").asString();
    }
    if (r.path("blob").isString()) {
      return "[resource " + Texts.str(r.get("uri"), "") + ", " + Texts.str(r.get("mimeType"), "") + ", "
          + size(r.get("blob").asString()) + ", not passed on by GateRook]";
    }
    return "[resource, not passed on by GateRook]";
  }

  /** The decoded size of Base64 data, "12 KB". */
  private static String size(String base64) {
    if (base64 == null) {
      return "0 bytes";
    }
    long bytes;
    try {
      bytes = Base64.getMimeDecoder().decode(base64).length;
    } catch (IllegalArgumentException e) {
      bytes = base64.length() * 3L / 4;
    }
    return bytes < 1024 ? bytes + " bytes" : (bytes / 1024) + " KB";
  }
}
