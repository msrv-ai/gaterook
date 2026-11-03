package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * An MCP server's {@code tools/call} result as the core's neutral {@link ToolResult} (report 11 §3.1 "Result",
 * ARCHITECTURE.md §7.5): text blocks pass as they are, and so do images, audio and embedded resources — the core
 * withholds one whose bytes carry a secret or that doesn't fit the call's limit. A link to a resource, and an embedded
 * resource's URI, are under the connection's namespace when the server shares its resources, so they read back
 * through the gateway; otherwise a link is text with its URI — data, never something a client would fetch on the
 * agent's behalf. {@code isError} of the server stays; the core masks and cuts the result like any other.
 */
final class Results {

  private Results() {}

  /** A result of a server whose resources aren't shared. */
  static ToolResult of(JsonNode r) {
    return of(r, null);
  }

  /**
   * @param sharing the connection's name when its server shares its resources, else {@code null}
   */
  static ToolResult of(JsonNode r, String sharing) {
    List<ToolResult.Content> content = new ArrayList<>();
    for (JsonNode c : r.path("content")) {
      content.add(block(c, sharing));
    }
    JsonNode s = r.get("structuredContent");
    JsonNode structured = s == null || s.isNull() ? null : s;
    boolean error = r.path("isError").asBoolean(false);
    return new ToolResult(List.copyOf(content), structured, error, null, false, null, List.of(), 0, 0, null);
  }

  /** The block a content block becomes. */
  static ToolResult.Content block(JsonNode c, String sharing) {
    String type = Texts.str(c.get("type"), "");
    return switch (type) {
      case "text" -> ToolResult.Content.text(Texts.str(c.get("text"), ""));
      case "image", "audio" -> {
        String data = Texts.str(c.get("data"), null);
        if (data == null || !base64(data)) {
          yield ToolResult.Content.text("[" + type + " " + Texts.str(c.get("mimeType"), "") + " without valid data, "
              + "not passed on by GateRook]");
        }
        String mime = Texts.clean(Texts.str(c.get("mimeType"), "application/octet-stream"), 120, false);
        yield type.equals("image") ? ToolResult.Content.image(data, mime) : ToolResult.Content.audio(data, mime);
      }
      case "resource_link" -> {
        String uri = Texts.str(c.get("uri"), "");
        String name = c.path("name").isString() ? Texts.clean(c.get("name").asString(), Snapshots.MAX_TITLE, false)
            : null;
        String mime = Texts.str(c.get("mimeType"), null);
        if (sharing != null && !uri.isEmpty()) {
          yield ToolResult.Content.link(Federation.exposed(sharing, uri), name, mime);
        }
        yield ToolResult.Content.text("Resource link (data, not fetched by GateRook): " + uri
            + (name == null ? "" : " · " + name) + (mime == null ? "" : " · " + mime));
      }
      case "resource" -> embedded(c.path("resource"), sharing);
      default -> ToolResult.Content.text("[" + Texts.clean(type.isEmpty() ? "unknown" : type, 40, false)
          + " content, not passed on by GateRook]");
    };
  }

  private static ToolResult.Content embedded(JsonNode r, String sharing) {
    String uri = Texts.str(r.get("uri"), "");
    String shown = sharing != null && !uri.isEmpty() ? Federation.exposed(sharing, uri) : uri;
    String mime = Texts.str(r.get("mimeType"), null);
    if (r.path("text").isString()) {
      return ToolResult.Content.resource(shown, mime, r.get("text").asString(), null);
    }
    if (r.path("blob").isString() && base64(r.get("blob").asString())) {
      return ToolResult.Content.resource(shown, mime, null, r.get("blob").asString());
    }
    return ToolResult.Content.text("[resource " + uri + ", not passed on by GateRook]");
  }

  /** Whether {@code data} is Base64 a client can decode (the core checks its bytes as Base64 too). */
  private static boolean base64(String data) {
    try {
      Base64.getDecoder().decode(data);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
