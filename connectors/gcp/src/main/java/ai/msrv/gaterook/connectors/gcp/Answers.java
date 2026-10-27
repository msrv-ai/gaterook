package ai.msrv.gaterook.connectors.gcp;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answers of {@code gcp_describe}: {@code structuredContent} of its output schema and the same as text, which is
 * what many clients show the model. The connector keeps the answer inside the size limit itself — whole resources of a
 * search, the longest lists of one resource's state — so the core never has to cut through it; a quarter of the limit
 * is left for masking and the core's fields ({@link Call#budget()}).
 */
final class Answers {

  /** Longest run of labels on one line of text; the structured item has them all. */
  static final int TEXT_LABELS = 160;

  private Answers() {}

  static ToolResult search(Call call, DescribeRequest r, List<ObjectNode> found, int hidden, int masked, String next,
      List<String> notes) {
    long budget = call.budget();
    List<ObjectNode> kept = new ArrayList<>(found);
    String reason = null;
    String cursor = next == null ? null : Cursors.seal(call.ctx(), AssetDescribe.PURPOSE, call.cfg().id(), r.hash(),
        next, null);
    while (true) {
      ObjectNode s = searchContent(r, kept, hidden, masked, cursor, reason, notes);
      String text = searchText(call, r, kept, cursor, s.path("notes"));
      if (Call.utf8(text) + Call.utf8(s.toString()) <= budget || kept.isEmpty()) {
        return ToolResult.json(s, text);
      }
      // Too large: whole resources go from the end, and a cursor would skip them, so the answer says so instead.
      kept.remove(kept.size() - 1);
      cursor = null;
      if (reason == null) {
        reason = "size_limit";
        notes = new ArrayList<>(notes);
        notes.add("The answer was cut to the size limit; narrow the query (assetTypes, query) to see the rest.");
      }
    }
  }

  private static ObjectNode searchContent(DescribeRequest r, List<ObjectNode> kept, int hidden, int masked,
      String cursor, String reason, List<String> notes) {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("provider", "gcp");
    s.put("scope", r.scope());
    s.put("mode", "search");
    s.set("resources", AssetDescribe.array(kept));
    s.putNull("resource");
    s.put("count", kept.size());
    s.put("hiddenByPolicy", hidden);
    s.put("truncated", cursor != null || reason != null);
    if (reason != null) {
      s.put("truncatedReason", reason);
    }
    if (cursor == null) {
      s.putNull("nextCursor");
    } else {
      s.put("nextCursor", cursor);
    }
    s.put("masked", masked);
    ArrayNode n = s.putArray("notes");
    notes.forEach(n::add);
    return s;
  }

  private static String searchText(Call call, DescribeRequest r, List<ObjectNode> kept, String cursor,
      JsonNode notes) {
    StringBuilder t = new StringBuilder();
    t.append(kept.size()).append(kept.size() == 1 ? " resource" : " resources").append(" in ").append(r.scope())
        .append(" (").append(call.cfg().name()).append(", Cloud Asset inventory)");
    t.append(kept.isEmpty() ? "." : ":");
    for (ObjectNode item : kept) {
      t.append('\n').append(item.path("name").asString("")).append(" · ").append(item.path("assetType").asString(""));
      if (item.has("location")) {
        t.append(" · ").append(item.path("location").asString());
      }
      if (item.has("state")) {
        t.append(" · ").append(item.path("state").asString());
      }
      if (item.has("labels")) {
        t.append(" · labels ").append(labels(item.path("labels")));
      }
    }
    for (JsonNode n : notes) {
      t.append('\n').append(n.asString());
    }
    if (cursor != null) {
      t.append("\nNext: call gcp_describe again with the same arguments and cursor ").append(cursor);
    }
    t.append('\n').append(AssetDescribe.DATA_NOTE);
    return OutputSanitizer.sanitize(t.toString());
  }

  static String labels(JsonNode labels) {
    StringBuilder l = new StringBuilder();
    for (Map.Entry<String, JsonNode> e : new TreeMap<>(labelMap(labels)).entrySet()) {
      if (!l.isEmpty()) {
        l.append(',');
      }
      l.append(e.getKey()).append('=').append(e.getValue().asString(e.getValue().toString()));
    }
    return l.length() <= TEXT_LABELS ? l.toString() : l.substring(0, TEXT_LABELS - 1) + "…";
  }

  private static Map<String, JsonNode> labelMap(JsonNode labels) {
    Map<String, JsonNode> out = new TreeMap<>();
    labels.properties().forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  static ToolResult get(Call call, DescribeRequest r, String scope, ObjectNode resource, int masked,
      List<String> notes) {
    long budget = call.budget();
    List<String> allNotes = new ArrayList<>(notes);
    String reason = null;
    JsonNode data = resource.get("data");
    if (data != null && Call.utf8(data.toString()) > budget - 4_096) {
      JsonNode cut = data.deepCopy();
      int keep = 64;
      while (Call.utf8(cut.toString()) > budget - 4_096 && keep >= 1) {
        cut = shrink(data, keep);
        keep /= 2;
      }
      resource.set("data", cut);
      reason = "size_limit";
      allNotes.add("The state was cut to the size limit: long lists keep their first items and long texts their "
          + "start.");
    }
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("provider", "gcp");
    s.put("scope", scope);
    s.put("mode", "get");
    s.putNull("resources");
    s.set("resource", resource);
    s.put("count", 1);
    s.put("hiddenByPolicy", 0);
    s.put("truncated", reason != null);
    if (reason != null) {
      s.put("truncatedReason", reason);
    }
    s.putNull("nextCursor");
    s.put("masked", masked);
    ArrayNode n = s.putArray("notes");
    allNotes.forEach(n::add);

    StringBuilder t = new StringBuilder();
    t.append(resource.path("name").asString("")).append(" · ").append(resource.path("assetType").asString(""));
    if (resource.has("location")) {
      t.append(" · ").append(resource.path("location").asString());
    }
    if (resource.has("state")) {
      t.append(" · ").append(resource.path("state").asString());
    }
    t.append("\nsource: ").append(resource.path("source").asString()).append(resource.path("source").asString()
        .equals("live") ? " (the service's API, now)" : " (Cloud Asset inventory, updated "
        + resource.path("inventoryUpdateTime").asString("at an unknown time") + ")");
    if (resource.has("labels")) {
      t.append("\nlabels: ").append(labels(resource.path("labels")));
    }
    if (masked > 0) {
      t.append('\n').append(masked).append(masked == 1 ? " field masked" : " fields masked")
          .append(" by the gateway (‹masked›).");
    }
    JsonNode shown = resource.get("data");
    if (shown != null) {
      String pretty = AssetDescribe.pretty(shown);
      if (Call.utf8(pretty) + Call.utf8(s.toString()) <= budget) {
        t.append("\nstate:\n").append(pretty);
      } else {
        t.append("\nThe state (").append(Call.utf8(shown.toString()) / 1024).append(" KB) is in structuredContent "
            + "resource.data.");
      }
    }
    for (String note : allNotes) {
      t.append('\n').append(note);
    }
    t.append('\n').append(AssetDescribe.DATA_NOTE);
    return ToolResult.json(s, OutputSanitizer.sanitize(t.toString()));
  }

  /** {@code node} with every list cut to its first {@code keep} items and every text to {@code keep * 64} chars. */
  static JsonNode shrink(JsonNode node, int keep) {
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      node.properties().forEach(e -> out.set(e.getKey(), shrink(e.getValue(), keep)));
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (int i = 0; i < Math.min(keep, node.size()); i++) {
        out.add(shrink(node.get(i), keep));
      }
      if (node.size() > keep) {
        out.add("… " + (node.size() - keep) + " more");
      }
      return out;
    }
    if (node.isString() && node.asString().length() > keep * 64) {
      return JsonNodeFactory.instance.textNode(node.asString().substring(0, keep * 64) + "…");
    }
    return node;
  }
}
