package ai.msrv.gaterook.core.connections.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The secret fields of a connection type: every {@code writeOnly} property of its {@code configSchema}, wherever it
 * sits ({@code oneOf} branches of Logs, items of HTTP's {@code secretHeaders}, the SSH bastion's credentials behind
 * a local {@code $ref}; {@link SchemaPointers} walks the schema). As JSON Pointer templates where
 * {@code *} stands for an array index: {@code /credentials/password}, {@code /secretHeaders/*}{@code /value}.
 *
 * <p>In a stored config a secret field holds only {@code {"$secret": "<secret id>"}}; the value lives in the secrets
 * module. Connectors get the config without these placeholders and the field names in
 * {@code ConnectionConfig.secretFields()}.
 */
public final class SecretFields {

  /** The key of a stored secret placeholder. */
  public static final String PLACEHOLDER = "$secret";

  private SecretFields() {}

  /** Marks a property that holds a public identifier secret scanners flag, such as an AWS access key id. */
  public static final String NOT_SECRET = "x-not-secret";

  /** Pointer templates of every writeOnly property. */
  public static List<String> templates(JsonNode schema) {
    return collect(schema, s -> s.path("writeOnly").asBoolean(false));
  }

  /**
   * Pointer templates of every property marked {@value #NOT_SECRET}: saving doesn't refuse a value there because it
   * looks like a credential (an {@code AKIA…} key id is the identifier of a key, its secret is a writeOnly field).
   */
  public static List<String> notSecretTemplates(JsonNode schema) {
    return collect(schema, s -> s.path(NOT_SECRET).asBoolean(false));
  }

  /** A leased login supplies its own user. Only the validation copy gets a stand-in; saved settings never do. */
  public static void dynamicLoginUsers(JsonNode schema, ObjectNode probe, Set<String> dynamicFields) {
    if (dynamicFields.isEmpty()) return;
    List<String> secretTemplates = templates(schema);
    for (SchemaPointers.Located login : SchemaPointers.collect(schema,
        node -> "login".equals(node.path("x-gateway-secret").asString("")))) {
      for (String parent : SchemaPointers.values(probe, login.template()).keySet()) {
        boolean supplied = dynamicFields.stream().anyMatch(field -> field.startsWith(parent + "/")
            && field.lastIndexOf('/') == parent.length()
            && matches(secretTemplates, field));
        if (!supplied) continue;
        for (Map.Entry<String, JsonNode> property : login.schema().path("properties").properties()) {
          if (property.getKey().matches("(?i)user|username|login")
              && "string".equals(property.getValue().path("type").asString(""))) {
            set(probe, parent + "/" + escape(property.getKey()),
                tools.jackson.databind.node.StringNode.valueOf("dynamic-login"));
          }
        }
      }
    }
  }

  private static List<String> collect(JsonNode schema, java.util.function.Predicate<JsonNode> wanted) {
    Set<String> out = new LinkedHashSet<>();
    SchemaPointers.collect(schema, wanted).forEach(l -> out.add(l.template()));
    return List.copyOf(out);
  }

  /** Whether a concrete pointer ({@code /secretHeaders/0/value}) is one of the templates. */
  public static boolean matches(List<String> templates, String pointer) {
    for (String t : templates) {
      if (match(t, pointer)) {
        return true;
      }
    }
    return false;
  }

  private static boolean match(String template, String pointer) {
    String[] t = template.split("/", -1);
    String[] p = pointer.split("/", -1);
    if (t.length != p.length) {
      return false;
    }
    for (int i = 0; i < t.length; i++) {
      if ("*".equals(t[i]) ? !p[i].matches("\\d{1,4}") : !t[i].equals(p[i])) {
        return false;
      }
    }
    return true;
  }

  /** Pointers of every stored placeholder, and the secret id each one names. */
  public static Map<String, String> placeholders(JsonNode config) {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    walk(config, "", out);
    return out;
  }

  private static void walk(JsonNode node, String pointer, Map<String, String> out) {
    if (node == null) {
      return;
    }
    if (isPlaceholder(node)) {
      out.put(pointer, node.get(PLACEHOLDER).asString());
      return;
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        walk(e.getValue(), pointer + "/" + escape(e.getKey()), out);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        walk(node.get(i), pointer + "/" + i, out);
      }
    }
  }

  public static boolean isPlaceholder(JsonNode node) {
    return node != null && node.isObject() && node.size() == 1 && node.path(PLACEHOLDER).isString();
  }

  /** Whether any object in the tree uses the placeholder key: never accepted from a request. */
  public static boolean mentionsPlaceholder(JsonNode node) {
    if (node == null) {
      return false;
    }
    if (node.isObject()) {
      if (node.has(PLACEHOLDER)) {
        return true;
      }
      for (JsonNode child : node) {
        if (mentionsPlaceholder(child)) {
          return true;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        if (mentionsPlaceholder(child)) {
          return true;
        }
      }
    }
    return false;
  }

  /** A copy of {@code config} without placeholders: what connectors get. */
  public static JsonNode strip(JsonNode config) {
    JsonNode copy = config.deepCopy();
    removePlaceholders(copy);
    return copy;
  }

  private static void removePlaceholders(JsonNode node) {
    if (node instanceof ObjectNode object) {
      List<String> remove = new ArrayList<>();
      for (Map.Entry<String, JsonNode> e : object.properties()) {
        if (isPlaceholder(e.getValue())) {
          remove.add(e.getKey());
        } else {
          removePlaceholders(e.getValue());
        }
      }
      remove.forEach(object::remove);
    } else if (node instanceof ArrayNode array) {
      for (int i = array.size() - 1; i >= 0; i--) {
        if (isPlaceholder(array.get(i))) {
          array.remove(i);
        } else {
          removePlaceholders(array.get(i));
        }
      }
    }
  }

  /**
   * Sets {@code value} at a concrete pointer, creating objects on the way (arrays must exist up to the index).
   *
   * @return whether the pointer could be reached
   */
  public static boolean set(ObjectNode root, String pointer, JsonNode value) {
    String[] parts = pointer.substring(1).split("/", -1);
    JsonNode current = root;
    for (int i = 0; i < parts.length - 1; i++) {
      String name = unescape(parts[i]);
      JsonNode next;
      if (current instanceof ObjectNode o) {
        next = o.get(name);
        if (next == null || next.isNull()) {
          next = o.putObject(name);
        }
      } else if (current instanceof ArrayNode a && name.matches("\\d{1,4}") && Integer.parseInt(name) < a.size()) {
        next = a.get(Integer.parseInt(name));
      } else {
        return false;
      }
      current = next;
    }
    String last = unescape(parts[parts.length - 1]);
    if (current instanceof ObjectNode o) {
      o.set(last, value);
      return true;
    }
    if (current instanceof ArrayNode a && last.matches("\\d{1,4}")) {
      int index = Integer.parseInt(last);
      if (index < a.size()) {
        a.set(index, value);
        return true;
      }
      if (index == a.size()) {
        a.add(value);
        return true;
      }
    }
    return false;
  }

  /** Whether the parent of {@code pointer} exists in {@code root} (an object or array the field can live in). */
  public static boolean parentExists(JsonNode root, String pointer) {
    int slash = pointer.lastIndexOf('/');
    JsonNode parent = slash <= 0 ? root : root.at(pointer.substring(0, slash));
    return parent.isObject() || parent.isArray();
  }

  /** {@code {"$secret": id}}. */
  public static ObjectNode placeholder(String secretId) {
    return JsonNodeFactory.instance.objectNode().put(PLACEHOLDER, secretId);
  }

  /** A secret's display name from its field: {@code /credentials/secretAccessKey} → {@code secret-access-key}. */
  public static String displayName(String pointer) {
    StringBuilder out = new StringBuilder();
    for (String part : pointer.substring(1).split("/")) {
      String name = unescape(part);
      if (name.equals("credentials") || name.equals("auth")) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append('-');
      }
      for (int i = 0; i < name.length(); i++) {
        char c = name.charAt(i);
        if (Character.isUpperCase(c)) {
          if (i > 0) {
            out.append('-');
          }
          out.append(Character.toLowerCase(c));
        } else if (Character.isLetterOrDigit(c)) {
          out.append(c);
        } else {
          out.append('-');
        }
      }
    }
    return out.isEmpty() ? "secret" : out.toString();
  }

  static String escape(String name) {
    return name.replace("~", "~0").replace("/", "~1");
  }

  static String unescape(String part) {
    return part.replace("~1", "/").replace("~0", "~");
  }
}
