package ai.msrv.gaterook.connectors.mcpserver;

import ai.msrv.gaterook.connector.api.ResourceDescriptor;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an MCP server shares besides tools, as an endpoint publishes it (ARCHITECTURE.md §5.4): its resources under the
 * connection's namespace ({@code mcpgw://connections/<name>/mcp/<the server's URI>}), its MCP Apps under the
 * connection's app namespace ({@code ui://gaterook/connections/<name>/<the rest of the server's ui:// URI>}), its
 * prompts under the connection's tool prefix. The mapping is plain and reversible: no two servers can answer for each
 * other's URIs, and a URI the server names in a result (a link, an embedded resource) reads back through it.
 *
 * <p>What the server says about them is untrusted text: names, titles and descriptions are cleaned and cut like a
 * tool's; icons and other {@code _meta} are left out (an icon is a URL a client would fetch), except an app's
 * {@code ui} settings, which its host needs to render it.
 */
final class Federation {

  static final int MAX_ITEMS = 1_000;

  private Federation() {}

  /** The URI an endpoint publishes for the server's {@code uri}. */
  static String exposed(String connection, String uri) {
    if (uri.startsWith("ui://")) {
      return ResourceDescriptor.appPrefix(connection) + uri.substring(5);
    }
    return ResourceDescriptor.prefix(connection) + "mcp/" + uri;
  }

  /** The server's URI for one an endpoint publishes, or empty when it isn't one of this connection's. */
  static Optional<String> upstream(String connection, String exposed) {
    String app = ResourceDescriptor.appPrefix(connection);
    if (exposed.startsWith(app) && exposed.length() > app.length()) {
      return Optional.of("ui://" + exposed.substring(app.length()));
    }
    String own = ResourceDescriptor.prefix(connection) + "mcp/";
    if (exposed.startsWith(own) && exposed.length() > own.length()) {
      return Optional.of(exposed.substring(own.length()));
    }
    return Optional.empty();
  }

  /** {@code resources/list} or {@code resources/templates/list} as an endpoint publishes it. */
  static ObjectNode resources(String connection, List<ObjectNode> listed, boolean templates) {
    ObjectNode out = Texts.JSON.createObjectNode();
    ArrayNode items = out.putArray(templates ? "resourceTemplates" : "resources");
    String key = templates ? "uriTemplate" : "uri";
    Set<String> seen = new HashSet<>();
    for (ObjectNode r : listed) {
      String uri = Texts.str(r.get(key), null);
      if (uri == null || uri.isEmpty() || uri.length() > 2_048 || Texts.invisible(uri) || !seen.add(uri)
          || items.size() >= MAX_ITEMS) {
        continue;
      }
      ObjectNode n = items.addObject();
      n.put(key, exposed(connection, uri));
      n.put("name", Texts.clean(Texts.str(r.get("name"), uri), Snapshots.MAX_TITLE, false));
      copyText(r, n, "title", Snapshots.MAX_TITLE);
      copyText(r, n, "description", Snapshots.MAX_DESCRIPTION);
      copyText(r, n, "mimeType", 200);
      if (r.path("size").canConvertToLong()) {
        n.put("size", r.get("size").asLong());
      }
      if (r.path("annotations").isObject()) {
        ObjectNode a = n.putObject("annotations");
        JsonNode audience = r.get("annotations").path("audience");
        if (audience.isArray()) {
          ArrayNode kept = a.putArray("audience");
          audience.forEach(x -> {
            if (x.asString("").equals("user") || x.asString("").equals("assistant")) {
              kept.add(x.asString());
            }
          });
        }
        if (r.get("annotations").path("priority").isNumber()) {
          a.put("priority", r.get("annotations").get("priority").asDouble());
        }
      }
    }
    return out;
  }

  /** The contents of a {@code resources/read} as blocks of a result, their URIs as the endpoint publishes them. */
  static ToolResult contents(String connection, JsonNode read) {
    List<ToolResult.Content> out = new ArrayList<>();
    for (JsonNode c : read.path("contents")) {
      String uri = Texts.str(c.get("uri"), "");
      String mime = Texts.str(c.get("mimeType"), null);
      ToolResult.Content block = c.path("blob").isString()
          ? ToolResult.Content.resource(exposed(connection, uri), mime, null, c.get("blob").asString())
          : ToolResult.Content.resource(exposed(connection, uri), mime, Texts.str(c.get("text"), ""), null);
      JsonNode ui = c.path("_meta").path("ui");
      if (ui.isObject()) {
        // An app's host needs its settings (the CSP of its connections and resources): kept as the server gave them.
        ObjectNode meta = Texts.JSON.createObjectNode();
        meta.set("ui", ui.deepCopy());
        block = block.withMeta(meta);
      }
      out.add(block);
    }
    return new ToolResult(List.copyOf(out), null, false, null, false, null, List.of(), 0, 0, null);
  }

  /** The prompts of {@code prompts/list} under the server's prefix, and the server's name of each. */
  record Prompts(ObjectNode list, Map<String, String> originals) {}

  static Prompts prompts(String prefix, List<ObjectNode> listed) {
    ObjectNode out = Texts.JSON.createObjectNode();
    ArrayNode items = out.putArray("prompts");
    Map<String, String> originals = new LinkedHashMap<>();
    Set<String> taken = new HashSet<>();
    for (ObjectNode p : listed) {
      String original = Texts.str(p.get("name"), null);
      ToolNameMapper.Name name = ToolNameMapper.map(prefix, original, taken, x -> Optional.empty());
      if (name.problem() != null || items.size() >= MAX_ITEMS) {
        continue;
      }
      taken.add(name.exposed());
      originals.put(name.exposed(), original);
      ObjectNode n = items.addObject();
      n.put("name", name.exposed());
      copyText(p, n, "title", Snapshots.MAX_TITLE);
      copyText(p, n, "description", Snapshots.MAX_DESCRIPTION);
      ArrayNode args = n.putArray("arguments");
      for (JsonNode a : p.path("arguments")) {
        String argName = Texts.str(a.get("name"), null);
        if (argName == null || argName.isEmpty() || argName.length() > 128 || Texts.invisible(argName)) {
          continue;
        }
        ObjectNode arg = args.addObject();
        arg.put("name", argName);
        copyText(a, arg, "title", Snapshots.MAX_TITLE);
        copyText(a, arg, "description", 500);
        arg.put("required", a.path("required").asBoolean(false));
      }
    }
    return new Prompts(out, Map.copyOf(originals));
  }

  /**
   * A {@code prompts/get} answer: its messages as they are, but every resource they name (an embedded resource, a
   * link) under the connection's namespace, and the description cleaned.
   */
  static ObjectNode prompt(String connection, JsonNode got) {
    ObjectNode out = Texts.JSON.createObjectNode();
    if (got.path("description").isString()) {
      out.put("description", Texts.clean(got.get("description").asString(), Snapshots.MAX_DESCRIPTION, true));
    }
    ArrayNode messages = out.putArray("messages");
    for (JsonNode m : got.path("messages")) {
      if (!(m.path("content") instanceof ObjectNode content)) {
        continue;
      }
      ObjectNode c = content.deepCopy();
      c.remove("_meta");
      if (c.path("resource") instanceof ObjectNode res && res.path("uri").isString()) {
        res.put("uri", exposed(connection, res.get("uri").asString()));
        res.remove("_meta");
      }
      if ("resource_link".equals(Texts.str(c.get("type"), null)) && c.path("uri").isString()) {
        c.put("uri", exposed(connection, c.get("uri").asString()));
        c.remove("icons");
      }
      ObjectNode message = messages.addObject();
      message.put("role", "assistant".equals(Texts.str(m.get("role"), null)) ? "assistant" : "user");
      message.set("content", c);
    }
    return out;
  }

  private static void copyText(JsonNode from, ObjectNode to, String field, int max) {
    String v = Texts.str(from.get(field), null);
    if (v != null) {
      to.put(field, Texts.clean(v, max, field.equals("description")));
    }
  }
}
