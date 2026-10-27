package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
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

  static ToolResult of(McpSchema.CallToolResult r) {
    List<ToolResult.Content> content = new ArrayList<>();
    for (McpSchema.Content c : r.content() == null ? List.<McpSchema.Content>of() : r.content()) {
      content.add(ToolResult.Content.text(text(c)));
    }
    JsonNode structured = r.structuredContent() == null ? null : Texts.JSON.valueToTree(r.structuredContent());
    boolean error = Boolean.TRUE.equals(r.isError());
    return new ToolResult(List.copyOf(content), structured, error, null, false, null, List.of(), 0, 0, null);
  }

  /** The text a content block becomes. */
  static String text(McpSchema.Content c) {
    return switch (c) {
      case McpSchema.TextContent t -> t.text() == null ? "" : t.text();
      case McpSchema.ImageContent i -> "[image " + i.mimeType() + ", " + size(i.data()) + ", not passed on by "
          + "GateRook]";
      case McpSchema.AudioContent a -> "[audio " + a.mimeType() + ", " + size(a.data()) + ", not passed on by "
          + "GateRook]";
      case McpSchema.ResourceLink l -> "Resource link (data, not fetched by GateRook): " + l.uri()
          + (l.name() == null ? "" : " · " + l.name()) + (l.mimeType() == null ? "" : " · " + l.mimeType());
      case McpSchema.EmbeddedResource e -> embedded(e.resource());
      default -> "[" + c.type() + " content, not passed on by GateRook]";
    };
  }

  private static String embedded(McpSchema.ResourceContents r) {
    if (r instanceof McpSchema.TextResourceContents t) {
      return "Resource " + t.uri() + ":\n" + (t.text() == null ? "" : t.text());
    }
    if (r instanceof McpSchema.BlobResourceContents b) {
      return "[resource " + b.uri() + ", " + b.mimeType() + ", " + size(b.blob()) + ", not passed on by GateRook]";
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
