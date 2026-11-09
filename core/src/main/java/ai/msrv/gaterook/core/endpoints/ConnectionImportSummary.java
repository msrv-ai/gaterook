package ai.msrv.gaterook.core.endpoints;

import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.connections.ConnectionForms;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a connection candidate would save, said for the review card: where it connects and every setting whose value is
 * not the connector's default for its environment. The person sees it without opening the editor, so a host or a
 * setting the AI chose is never hidden behind a click.
 *
 * <p>Values are cut for reading, from their end: the start of a value, where a URL's host sits, always stays. The
 * settings that say where credentials go ({@link ConnectionForms#isTargetKey}) are listed first and are not cut with
 * the others, so a long config cannot push a host out of sight. A
 * candidate's config holds no credential (the import refuses them); the sources of its prepared credentials are listed
 * beside this summary by the review, as sources only.
 */
final class ConnectionImportSummary {

  /** Other settings listed, after the ones that say where credentials go; the rest is counted. */
  static final int MAX_SETTINGS = 40;

  /** Settings that say where credentials go, all of them but for an absurd number: a host is never left to a click. */
  static final int MAX_TARGETS = 100;

  private static final int MAX_NODES = 10_000;
  private static final int MAX_DEPTH = 24;
  private static final int VALUE_CHARS = 200;
  private static final int TARGET_CHARS = 512;

  private ConnectionImportSummary() {}

  private record Row(String path, String value, boolean target) {}

  /**
   * @param config the candidate's config, or a draft of it the person changed
   * @param schema the connector's config schema, for defaults and the form's default port
   * @return {@code {target, settings: [{path, value, target}], omitted}}; {@code target} is {@code null} when the
   *     config names none
   */
  static ObjectNode of(JsonNode config, JsonNode schema, Env env) {
    Walk walk = new Walk(schema, env);
    walk.object(config, schema, "", 0);
    List<Row> targets = walk.rows.stream().filter(Row::target).toList();
    List<Row> others = walk.rows.stream().filter(row -> !row.target()).toList();
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    String target = ConnectionForms.oneLine(ConnectionForms.targetOf(config, schema), TARGET_CHARS);
    if (target == null) {
      target = targets.stream().map(Row::value).findFirst().orElse(null);
    }
    out.put("target", target);
    ArrayNode settings = out.putArray("settings");
    List<Row> listed = new ArrayList<>(targets.stream().limit(MAX_TARGETS).toList());
    others.stream().limit(MAX_SETTINGS).forEach(listed::add);
    listed.forEach(row -> settings.addObject().put("path", row.path()).put("value", row.value())
        .put("target", row.target()));
    out.put("omitted", walk.rows.size() - listed.size());
    return out;
  }

  private static final class Walk {
    private final JsonNode root;
    private final Env env;
    private final List<Row> rows = new ArrayList<>();
    private int nodes;

    Walk(JsonNode root, Env env) {
      this.root = root;
      this.env = env;
    }

    void object(JsonNode value, JsonNode schema, String path, int depth) {
      if (!value.isObject() || depth > MAX_DEPTH) {
        return;
      }
      for (Map.Entry<String, JsonNode> property : value.properties()) {
        if (++nodes > MAX_NODES) {
          return;
        }
        String key = property.getKey();
        List<JsonNode> definitions = ConnectionImportService.propertySchemas(schema, root, key, new HashSet<>(), 0);
        member(property.getValue(), definitions, path.isEmpty() ? key : path + "." + key, key, depth + 1);
      }
    }

    private void member(JsonNode value, List<JsonNode> definitions, String path, String key, int depth) {
      if (value.isObject()) {
        object(value, union(definitions), path, depth);
      } else if (value.isArray()) {
        if (value.isEmpty() || depth > MAX_DEPTH) {
          return;
        }
        if (value.valueStream().allMatch(item -> item.isValueNode() && !item.isNull())) {
          setting(value, definitions, path, key);
          return;
        }
        List<JsonNode> items = ConnectionImportService.itemSchemas(union(definitions), root, new HashSet<>(), 0);
        for (int i = 0; i < value.size() && ++nodes <= MAX_NODES; i++) {
          member(value.get(i), items, path + "[" + i + "]", key, depth + 1);
        }
      } else {
        setting(value, definitions, path, key);
      }
    }

    private void setting(JsonNode value, List<JsonNode> definitions, String path, String key) {
      if (value.isNull() || (value.isString() && value.asString().isBlank()) || isDefault(value, definitions)) {
        return;
      }
      boolean target = ConnectionForms.isTargetKey(key);
      String text;
      if (value.isArray()) {
        List<String> parts = new ArrayList<>();
        value.forEach(item -> parts.add(item.asString()));
        text = String.join(", ", parts);
      } else {
        text = value.asString();
      }
      String line = ConnectionForms.oneLine(text, target ? TARGET_CHARS : VALUE_CHARS);
      if (line != null) {
        rows.add(new Row(path, line, target));
      }
    }

    /** The connector's own default for this setting, or the one its environment starts with. */
    private boolean isDefault(JsonNode value, List<JsonNode> definitions) {
      for (JsonNode definition : definitions) {
        if (same(value, definition.get("default")) || same(value, definition.path("x-env-default").get(env.id()))) {
          return true;
        }
      }
      return false;
    }

    private static boolean same(JsonNode value, JsonNode other) {
      if (other == null || other.isNull()) {
        return false;
      }
      return value.equals(other)
          || (value.isNumber() && other.isNumber() && value.decimalValue().compareTo(other.decimalValue()) == 0);
    }

    private static JsonNode union(List<JsonNode> definitions) {
      ObjectNode union = JsonNodeFactory.instance.objectNode();
      union.putArray("anyOf").addAll(definitions);
      return union;
    }
  }
}
