package ai.msrv.gaterook.connectors.aws;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answers of {@code aws_describe}: {@code structuredContent} of its output schema and the same as text, which is
 * what many clients show the model. The connector keeps the answer inside the size limit itself — whole resources of a
 * list (the cursor then goes on after the last one shown), the longest lists of one resource's state — so the core
 * never has to cut through it; a quarter of the limit is left for masking and the core's fields ({@link Call#budget}).
 */
final class Answers {

  /** Longest run of tags on one line of text; the structured item has them all. */
  static final int TEXT_TAGS = 160;
  /** Longest properties of one listed resource in the text; the structured item has them all. */
  static final int TEXT_PROPERTIES = 600;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Answers() {}

  static ToolResult list(Call call, DescribeRequest r, List<CloudControlDescribe.Found> found, int hidden,
      int masked, Cursors.Position next, List<String> notes) {
    long budget = call.budget();
    List<CloudControlDescribe.Found> kept = new ArrayList<>(found);
    Cursors.Position at = next;
    String reason = null;
    boolean textProperties = true;
    List<String> allNotes = new ArrayList<>(notes);
    while (true) {
      String cursor = at == null ? null : Cursors.seal(call.ctx(), CloudControlDescribe.PURPOSE, call.cfg().id(),
          r.hash(), at, null);
      ObjectNode s = listContent(call, r, kept, hidden, masked, cursor, reason, allNotes);
      String text = listText(call, r, kept, hidden, cursor, allNotes, textProperties);
      if (Call.utf8(text) + Call.utf8(s.toString()) <= budget || kept.isEmpty()) {
        return ToolResult.json(s, text);
      }
      if (textProperties) {
        // First the text goes without properties: structuredContent still has them.
        textProperties = false;
        continue;
      }
      if (kept.size() == 1) {
        // One resource alone is too large: it stays, cut like a single resource's state; removing it would leave an
        // empty page and a cursor past it, and the agent would never see it.
        CloudControlDescribe.Found only = kept.getFirst();
        ObjectNode view = only.view().deepCopy();
        JsonNode props = view.path("properties");
        int keep = 64;
        while (props.isObject() && keep >= 1 && Call.utf8(view.toString()) > budget - 4_096) {
          view.set("properties", shrink(props, keep));
          keep /= 2;
        }
        if (Call.utf8(view.toString()) > budget - 4_096) {
          view.putObject("properties");
        }
        view.put("complete", false);
        kept.set(0, new CloudControlDescribe.Found(view, only.after()));
        reason = "size_limit";
        allNotes.add("The properties of " + view.path("identifier").asString("the resource") + " were cut to the "
            + "size limit: pass identifier for its whole state, or properties to choose some.");
        String c = at == null ? null : Cursors.seal(call.ctx(), CloudControlDescribe.PURPOSE, call.cfg().id(),
            r.hash(), at, null);
        return ToolResult.json(listContent(call, r, kept, hidden, masked, c, reason, allNotes),
            listText(call, r, kept, hidden, c, allNotes, false));
      }
      // Too large: whole resources go from the end; the cursor goes on after the last one shown.
      kept.remove(kept.size() - 1);
      at = kept.getLast().after();
      if (reason == null) {
        reason = "size_limit";
        allNotes.add("The answer was cut to the size limit; the cursor goes on from the last resource shown, or pass "
            + "properties to see fewer properties per resource.");
      }
    }
  }

  private static ObjectNode listContent(Call call, DescribeRequest r, List<CloudControlDescribe.Found> kept,
      int hidden, int masked, String cursor, String reason, List<String> notes) {
    ObjectNode s = header(call, r, "list");
    ArrayNode items = s.putArray("resources");
    kept.forEach(f -> items.add(f.view()));
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
    s.put("note", CloudControlDescribe.LIST_NOTE);
    ArrayNode n = s.putArray("notes");
    notes.forEach(n::add);
    return s;
  }

  private static String listText(Call call, DescribeRequest r, List<CloudControlDescribe.Found> kept, int hidden,
      String cursor, List<String> notes, boolean withProperties) {
    StringBuilder t = new StringBuilder();
    t.append(kept.size()).append(' ').append(r.type()).append(" in ").append(r.region()).append(" (")
        .append(call.cfg().name()).append(", Cloud Control)").append(kept.isEmpty() ? "." : ":");
    for (CloudControlDescribe.Found f : kept) {
      ObjectNode v = f.view();
      t.append('\n').append(v.path("identifier").asString(""));
      if (v.path("status").isString()) {
        t.append(" · ").append(v.path("status").asString());
      }
      if (v.path("arn").isString() && !v.path("arn").asString().equals(v.path("identifier").asString())) {
        t.append(" · ").append(v.path("arn").asString());
      }
      if (!v.path("tags").isEmpty()) {
        t.append(" · tags ").append(tags(v.path("tags")));
      }
      if (withProperties && !v.path("properties").isEmpty()) {
        String props = v.path("properties").toString();
        t.append("\n  ").append(props.length() <= TEXT_PROPERTIES ? props
            : props.substring(0, TEXT_PROPERTIES - 1) + "… (all in structuredContent)");
      }
    }
    if (hidden > 0) {
      t.append('\n').append(hidden).append(hidden == 1 ? " resource" : " resources").append(" hidden by the ")
          .append(call.policy().env().id()).append(" policy.");
    }
    for (String n : notes) {
      t.append('\n').append(n);
    }
    if (cursor != null) {
      t.append("\nNext: call aws_describe again with the same arguments and cursor ").append(cursor);
    }
    t.append('\n').append(CloudControlDescribe.LIST_NOTE);
    t.append('\n').append(CloudControlDescribe.DATA_NOTE);
    return OutputSanitizer.sanitize(t.toString());
  }

  static ToolResult get(Call call, DescribeRequest r, ObjectNode resource, int masked) {
    long budget = call.budget();
    List<String> notes = new ArrayList<>(r.notes());
    String reason = null;
    JsonNode props = resource.get("properties");
    if (props != null && Call.utf8(props.toString()) > budget - 4_096) {
      JsonNode cut = props.deepCopy();
      int keep = 64;
      while (Call.utf8(cut.toString()) > budget - 4_096 && keep >= 1) {
        cut = shrink(props, keep);
        keep /= 2;
      }
      resource.set("properties", cut);
      reason = "size_limit";
      notes.add("The state was cut to the size limit: long lists keep their first items and long texts their start.");
    }
    ObjectNode s = header(call, r, "get");
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
    notes.forEach(n::add);

    StringBuilder t = new StringBuilder();
    t.append(r.type()).append(' ').append(resource.path("identifier").asString("")).append(" in ").append(r.region())
        .append(" (").append(call.cfg().name()).append(", Cloud Control GetResource)");
    if (resource.path("status").isString()) {
      t.append("\nstatus: ").append(resource.path("status").asString());
    }
    if (resource.path("arn").isString()) {
      t.append("\narn: ").append(resource.path("arn").asString());
    }
    if (!resource.path("tags").isEmpty()) {
      t.append("\ntags: ").append(tags(resource.path("tags")));
    }
    if (masked > 0) {
      t.append('\n').append(masked).append(masked == 1 ? " field masked" : " fields masked")
          .append(" by the gateway (‹masked›).");
    }
    JsonNode shown = resource.get("properties");
    if (shown != null && !shown.isEmpty()) {
      String pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(shown);
      if (Call.utf8(pretty) + Call.utf8(s.toString()) <= budget) {
        t.append("\nproperties:\n").append(pretty);
      } else {
        t.append("\nThe properties (").append(Call.utf8(shown.toString()) / 1024).append(" KB) are in "
            + "structuredContent resource.properties.");
      }
    }
    for (String note : notes) {
      t.append('\n').append(note);
    }
    t.append('\n').append(CloudControlDescribe.DATA_NOTE);
    return ToolResult.json(s, OutputSanitizer.sanitize(t.toString()));
  }

  private static ObjectNode header(Call call, DescribeRequest r, String mode) {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("provider", "aws");
    String account = call.settings().account();
    if (account != null) {
      s.put("account", account);
    }
    s.put("region", r.region());
    s.put("type", r.type());
    s.put("mode", mode);
    return s;
  }

  static String tags(JsonNode tags) {
    StringBuilder l = new StringBuilder();
    Map<String, JsonNode> sorted = new TreeMap<>();
    tags.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
    for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
      if (!l.isEmpty()) {
        l.append(',');
      }
      l.append(e.getKey()).append('=').append(e.getValue().asString(e.getValue().toString()));
    }
    return l.length() <= TEXT_TAGS ? l.toString() : l.substring(0, TEXT_TAGS - 1) + "…";
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
