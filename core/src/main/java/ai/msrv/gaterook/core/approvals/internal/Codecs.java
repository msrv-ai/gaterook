package ai.msrv.gaterook.core.approvals.internal;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON forms an approval request stores: its {@code action} ({@link ActionPreview}, by hand: SPI records are never
 * bound by reflection, report 15) and its masked result.
 */
public final class Codecs {

  private Codecs() {}

  public static ObjectNode action(ActionPreview p) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("kind", p.kind().name());
    n.put("language", p.language());
    n.put("text", p.text());
    n.put("target", p.target());
    n.put("summary", p.summary());
    n.set("fields", fields(p.fields()));
    n.set("impact", fields(p.impact()));
    ArrayNode warnings = n.putArray("warnings");
    p.warnings().forEach(warnings::add);
    ObjectNode facets = n.putObject("auditFacets");
    p.auditFacets().forEach(facets::put);
    n.set("payload", p.payload());
    n.set("extra", p.extra());
    return n;
  }

  public static ActionPreview action(JsonNode n) {
    ActionPreview.Kind kind = ActionPreview.Kind.valueOf(n.path("kind").asString("TEXT"));
    Map<String, String> facets = new LinkedHashMap<>();
    n.path("auditFacets").properties().forEach(e -> facets.put(e.getKey(), e.getValue().asString()));
    List<String> warnings = new ArrayList<>();
    n.path("warnings").forEach(w -> warnings.add(w.asString()));
    JsonNode payload = n.path("payload");
    JsonNode extra = n.path("extra");
    return new ActionPreview(kind, n.path("language").asString(null), n.path("text").asString(""),
        n.path("target").asString(null), n.path("summary").asString(null), fields(n.path("fields")),
        fields(n.path("impact")), List.copyOf(warnings), Map.copyOf(facets),
        payload.isMissingNode() || payload.isNull() ? null : payload,
        extra.isMissingNode() || extra.isNull() ? null : extra);
  }

  private static ArrayNode fields(List<ActionPreview.Field> fields) {
    ArrayNode a = JsonNodeFactory.instance.arrayNode();
    for (ActionPreview.Field f : fields) {
      a.addObject().put("label", f.label()).put("value", f.value());
    }
    return a;
  }

  private static List<ActionPreview.Field> fields(JsonNode n) {
    List<ActionPreview.Field> out = new ArrayList<>();
    n.forEach(f -> out.add(new ActionPreview.Field(f.path("label").asString(""), f.path("value").asString(""))));
    return List.copyOf(out);
  }

  /** A masked result as kept on the request for 60 minutes. */
  public static ObjectNode result(ToolResult r) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    ArrayNode content = n.putArray("content");
    for (ToolResult.Content c : r.content()) {
      ObjectNode o = content.addObject();
      o.put("type", c.type());
      if (c.text() != null) {
        o.put("text", c.text());
      }
      if (c.uri() != null) {
        o.put("uri", c.uri());
      }
      if (c.mimeType() != null) {
        o.put("mimeType", c.mimeType());
      }
      if (c.name() != null) {
        o.put("name", c.name());
      }
    }
    n.set("structuredContent", r.structuredContent());
    n.put("isError", r.isError());
    n.put("truncated", r.truncated());
    n.put("truncatedReason", r.truncatedReason());
    n.put("maskedCount", r.maskedCount());
    n.put("resultBytes", r.resultBytes());
    return n;
  }

  public static ToolResult result(JsonNode n) {
    List<ToolResult.Content> content = new ArrayList<>();
    n.path("content").forEach(c -> content.add(new ToolResult.Content(c.path("type").asString("text"),
        c.path("text").asString(null), c.path("uri").asString(null), c.path("mimeType").asString(null),
        c.path("name").asString(null))));
    JsonNode structured = n.path("structuredContent");
    return new ToolResult(List.copyOf(content), structured.isMissingNode() || structured.isNull() ? null : structured,
        n.path("isError").asBoolean(false), null, n.path("truncated").asBoolean(false),
        n.path("truncatedReason").asString(null), List.of(), n.path("maskedCount").asInt(0),
        n.path("resultBytes").asLong(0), null);
  }
}
