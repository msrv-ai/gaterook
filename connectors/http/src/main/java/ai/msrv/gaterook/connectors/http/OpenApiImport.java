package ai.msrv.gaterook.connectors.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The operations of an OpenAPI document as definition entries (report 10 §3.6, §6.5, §7.4): one per path × method,
 * its parameters (path level, then operation level, by {@code in} and {@code name}) and JSON or form body as one
 * {@code inputSchema} — path, query and header parameters as top-level properties, the body as {@code body} — and the
 * binding that turns the agent's arguments back into the request. What can't be sent faithfully is skipped with the
 * reason: cookie parameters, parameters with {@code content}, unsupported styles, multipart bodies, schemas over
 * 32 KB — and so is what only a hostile file has: a path over 2,048 characters, more than 200 parameters.
 */
final class OpenApiImport {

  static final int MAX_INPUT_SCHEMA_BYTES = 32 * 1_024;
  /** The core's validator refuses schemas with more subschemas than that at call time. */
  static final int MAX_SCHEMA_NODES = 2_000;
  /**
   * Bounds of what one file may make the import do (GitHub's description: 1,242 operations, paths under 200
   * characters, at most a few dozen parameters an operation): past them a file is refused or an operation skipped.
   */
  static final int MAX_FILE_OPERATIONS = 10_000;
  static final int MAX_PATH = 2_048;
  static final int MAX_PARAMETERS = 200;

  private static final Pattern PROPERTY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
  private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}/]+)}");
  /** Ignored as parameters by OpenAPI itself (3.0.3 §4.7.12.1): the media types and the credentials are the gateway's. */
  private static final Set<String> IGNORED_HEADERS = Set.of("authorization", "accept", "content-type");
  private static final Pattern NOT_WORD = Pattern.compile("[^A-Za-z0-9_]");
  // Linear on any path: "[^}]*" and "([A-Z]+)" backtracked over a long run of '{' or capitals (quadratic).
  private static final Pattern PLACEHOLDER_WORDS = Pattern.compile("\\{([^{}]*)}");
  private static final Pattern ACRONYM = Pattern.compile("([A-Z])([A-Z][a-z])");
  private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");
  private static final Pattern NOT_SNAKE = Pattern.compile("[^a-z0-9]+");
  private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");

  /** One operation and what it becomes; {@code skipped} non-null means it can't become a tool. */
  record Entry(String opKey, String method, String path, String operationId, String summary, List<String> tags,
      String nature, ObjectNode definition, String skipped) {}

  /**
   * The names of the built-in tools and their families ({@code sql_}, {@code http_}…), as the core knows them: a
   * template tool never takes one (ARCHITECTURE.md §9 "does not pass itself off as built-in").
   */
  record Reserved(Set<String> names, Set<String> prefixes) {
    static final Reserved NONE = new Reserved(Set.of(), Set.of());

    /** From the import options the core adds: {@code {"names":[…],"prefixes":["sql_",…]}}. */
    static Reserved of(JsonNode n) {
      Set<String> names = new HashSet<>();
      Set<String> prefixes = new HashSet<>();
      n.path("names").forEach(v -> names.add(v.asString("")));
      n.path("prefixes").forEach(v -> prefixes.add(v.asString("")));
      names.remove("");
      prefixes.remove("");
      return new Reserved(Set.copyOf(names), Set.copyOf(prefixes));
    }

    /** Whether a template prefix is the family of built-in tools ({@code http} of {@code http_get}). */
    boolean family(String prefix) {
      return !prefix.isEmpty() && prefixes.contains(prefix + "_");
    }

    boolean startsWithFamily(String toolName) {
      return prefixes.stream().anyMatch(toolName::startsWith);
    }
  }

  private final JsonNode root;
  private final OpenApiSchemas schemas;
  private final Set<String> gatewayHeaders;
  private final String authQuery;
  private final Reserved reserved;

  /**
   * @param gatewayHeaders headers the connection sets itself (its auth header, its secret headers), lower case: an
   *                       operation's parameter of that name is left out of the input
   * @param authQuery      the query parameter of {@code apiKeyQuery}, or {@code null}
   * @param reserved       names no tool of the template may take
   */
  OpenApiImport(JsonNode root, Set<String> gatewayHeaders, String authQuery, Reserved reserved) {
    this.root = root;
    this.schemas = new OpenApiSchemas(root);
    this.gatewayHeaders = gatewayHeaders;
    this.authQuery = authQuery;
    this.reserved = reserved;
  }

  /**
   * Every operation in the document's order, named under {@code prefix} without collisions.
   *
   * @throws OpenApiDocument.Rejected for a file with more than {@link #MAX_FILE_OPERATIONS} operations, or whose
   *                                  schemas expand past {@link OpenApiSchemas#MAX_TOTAL_WORK} once inlined
   */
  List<Entry> entries(String prefix) throws OpenApiDocument.Rejected {
    List<Entry> out = new ArrayList<>();
    Set<String> names = new HashSet<>(reserved.names());
    for (Map.Entry<String, JsonNode> pe : root.path("paths").properties()) {
      String path = pe.getKey();
      JsonNode item = OpenApiDocument.deref(root, pe.getValue());
      if (item == null || !item.isObject()) {
        continue;
      }
      for (Map.Entry<String, JsonNode> me : item.properties()) {
        String m = me.getKey().toLowerCase(Locale.ROOT);
        if (!OpenApiDocument.METHODS.contains(m) || !me.getValue().isObject()) {
          continue;
        }
        if (out.size() >= MAX_FILE_OPERATIONS) {
          throw new OpenApiDocument.Rejected("The file has more than " + String.format(Locale.US, "%,d",
              MAX_FILE_OPERATIONS) + " operations. Import a smaller file with only the paths you need.");
        }
        out.add(entry(path, m.toUpperCase(Locale.ROOT), item, me.getValue(), prefix, names));
        if (schemas.totalWork() > OpenApiSchemas.MAX_TOTAL_WORK) {
          throw new OpenApiDocument.Rejected("The file's schemas expand to more than " + String.format(Locale.US,
              "%,d", OpenApiSchemas.MAX_TOTAL_WORK) + " characters once their references are filled in. Import a "
              + "smaller file with only the paths you need.");
        }
      }
    }
    return out;
  }

  /** The work the entries took so far (see {@link OpenApiSchemas}): the corpus test reports it against the budget. */
  long work() {
    return schemas.totalWork();
  }

  private Entry entry(String path, String method, JsonNode item, JsonNode op, String prefix, Set<String> names) {
    String opKey = method + " " + path;
    String operationId = op.path("operationId").isString() ? ToolText.clean(op.get("operationId").asString(), 120)
        : null;
    String summary = ToolText.clean(op.path("summary").asString(""), ToolText.TITLE);
    List<String> tags = new ArrayList<>();
    for (JsonNode t : op.path("tags")) {
      tags.add(ToolText.clean(t.asString(""), 64));
    }
    String nature = method.equals("GET") || method.equals("HEAD") ? "read" : "mutation";
    String skipped = null;
    schemas.resetCount();
    schemas.spend(Math.min(path.length(), MAX_PATH + 1L));
    if (path.length() > MAX_PATH) {
      return new Entry(opKey, method, path, operationId, summary, tags, nature, null, "the path is longer than "
          + String.format(Locale.US, "%,d", MAX_PATH) + " characters");
    }
    if (!path.startsWith("/") || path.contains("?") || path.contains("#")
        || path.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
      return new Entry(opKey, method, path, operationId, summary, tags, nature, null, "the path isn't a plain path");
    }

    Map<String, JsonNode> params = new LinkedHashMap<>();
    for (JsonNode p : item.path("parameters")) {
      JsonNode d = OpenApiDocument.deref(root, p);
      if (d != null) {
        params.put(d.path("in").asString("") + ":" + d.path("name").asString(""), d);
      }
    }
    for (JsonNode p : op.path("parameters")) {
      JsonNode d = OpenApiDocument.deref(root, p);
      if (d != null) {
        params.put(d.path("in").asString("") + ":" + d.path("name").asString(""), d);
      }
    }
    schemas.spend(params.size());
    if (params.size() > MAX_PARAMETERS) {
      return new Entry(opKey, method, path, operationId, summary, tags, nature, null, "more than " + MAX_PARAMETERS
          + " parameters");
    }

    ObjectNode input = JsonNodeFactory.instance.objectNode();
    input.put("type", "object");
    input.put("additionalProperties", false);
    ObjectNode properties = input.putObject("properties");
    ArrayNode required = JsonNodeFactory.instance.arrayNode();
    ArrayNode bindings = JsonNodeFactory.instance.arrayNode();
    Set<String> taken = new HashSet<>();
    Set<String> pathParams = new HashSet<>();
    Matcher placeholders = PATH_PARAMETER.matcher(path);
    while (placeholders.find()) {
      pathParams.add(placeholders.group(1));
    }
    for (JsonNode p : params.values()) {
      String where = p.path("in").asString("");
      String name = p.path("name").asString("");
      if (name.isEmpty()) {
        continue;
      }
      if (where.equals("cookie")) {
        skipped = "cookie parameter " + name;
        break;
      }
      if (where.equals("header") && (IGNORED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
          || gatewayHeaders.contains(name.toLowerCase(Locale.ROOT)))) {
        continue;
      }
      if (where.equals("query") && name.equals(authQuery)) {
        continue;
      }
      if (where.equals("header") && (Headers.isForbidden(name) || !Headers.validName(name))) {
        if (p.path("required").asBoolean(false)) {
          skipped = "header " + name + ", which the gateway doesn't let agents send";
          break;
        }
        continue;
      }
      if (!p.has("schema")) {
        skipped = "parameter " + name + " described with content instead of a schema";
        break;
      }
      String style = p.path("style").asString(where.equals("query") ? "form" : "simple");
      boolean explode = p.path("explode").asBoolean(style.equals("form"));
      boolean styleOk = switch (where) {
        case "path", "header" -> style.equals("simple");
        case "query" -> style.equals("form") || (style.equals("deepObject") && explode);
        default -> false;
      };
      if (!styleOk) {
        skipped = where.isEmpty() ? "parameter " + name + " without a location"
            : where + " parameter " + name + " in style " + style;
        break;
      }
      if (where.equals("path") && !pathParams.contains(name)) {
        continue; // declared but not in this path: nothing to fill
      }
      String arg = argName(name, where, taken);
      JsonNode converted = schemas.convert(p.get("schema"));
      ObjectNode ps = converted.isObject() ? (ObjectNode) converted : JsonNodeFactory.instance.objectNode();
      if (p.path("description").isString() && !ps.has("description")) {
        ps.put("description", ToolText.clean(p.get("description").asString(), ToolText.PROPERTY_DESCRIPTION));
      }
      if (where.equals("header") && !ps.has("type") && !ps.has("enum") && !ps.has("const")) {
        ps.put("type", "string");
      }
      properties.set(arg, ps);
      boolean isRequired = where.equals("path") || p.path("required").asBoolean(false);
      if (isRequired) {
        required.add(arg);
      }
      ObjectNode b = bindings.addObject();
      b.put("arg", arg);
      b.put("in", where);
      b.put("name", name);
      b.put("required", isRequired);
      b.put("style", style);
      b.put("explode", explode);
    }
    if (skipped == null) {
      for (String placeholder : pathParams) {
        boolean bound = false;
        for (JsonNode b : bindings) {
          bound |= b.path("in").asString("").equals("path") && b.path("name").asString("").equals(placeholder);
        }
        if (!bound) {
          skipped = "path parameter {" + placeholder + "} isn't described";
          break;
        }
      }
    }

    ObjectNode body = null;
    JsonNode requestBody = OpenApiDocument.deref(root, op.get("requestBody"));
    if (skipped == null && requestBody != null && requestBody.isObject()) {
      String mediaType = null;
      JsonNode media = null;
      for (Map.Entry<String, JsonNode> ce : requestBody.path("content").properties()) {
        String type = ce.getKey().toLowerCase(Locale.ROOT);
        String bare = type.contains(";") ? type.substring(0, type.indexOf(';')).strip() : type;
        if (bare.equals("application/json") || (bare.endsWith("+json") && mediaType == null)) {
          mediaType = bare;
          media = ce.getValue();
          if (bare.equals("application/json")) {
            break;
          }
        } else if (bare.equals("application/x-www-form-urlencoded") && mediaType == null) {
          mediaType = bare;
          media = ce.getValue();
        }
      }
      if (mediaType == null) {
        List<String> types = new ArrayList<>();
        requestBody.path("content").propertyNames().forEach(types::add);
        if (!types.isEmpty()) {
          skipped = String.join(", ", types) + " body";
        }
      } else {
        String arg = taken.contains("body") ? "requestBody" : "body";
        taken.add(arg);
        JsonNode converted = schemas.convert(media.path("schema"));
        if (requestBody.path("description").isString() && converted.isObject() && !converted.has("description")) {
          ((ObjectNode) converted).put("description", ToolText.clean(requestBody.get("description").asString(),
              ToolText.PROPERTY_DESCRIPTION));
        }
        properties.set(arg, converted);
        boolean bodyRequired = requestBody.path("required").asBoolean(false);
        if (bodyRequired) {
          required.add(arg);
        }
        body = JsonNodeFactory.instance.objectNode();
        body.put("arg", arg);
        body.put("mediaType", mediaType);
        body.put("required", bodyRequired);
      }
    }
    if (!required.isEmpty()) {
      input.set("required", required);
    }
    if (skipped == null && schemas.overBudget()) {
      // Cut short while inlining: it would serialize to more than 32 KB, and it isn't serialized to find out.
      skipped = "the input schema is more than 32 KB";
    }
    if (skipped == null) {
      int bytes = JsonText.compact(input).getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_INPUT_SCHEMA_BYTES) {
        skipped = "the input schema is " + Sizes.text(bytes) + ", more than 32 KB";
      } else if (schemas.nodes() > MAX_SCHEMA_NODES) {
        skipped = "the input schema has more than " + MAX_SCHEMA_NODES + " parts";
      }
    }
    if (skipped != null) {
      return new Entry(opKey, method, path, operationId, summary, tags, nature, null, skipped);
    }

    String candidate = toolName(prefix, operationId, method, path);
    if (reserved.startsWithFamily(candidate)) {
      // Only without a prefix: an operation sqlQuery would read as the built-in sql_query.
      candidate = toolName("op", operationId, method, path);
    }
    String name = unique(candidate, names);
    String description = summary;
    String longer = ToolText.clean(op.path("description").asString(""), ToolText.TOOL_DESCRIPTION);
    if (longer != null && !longer.isEmpty() && !longer.equals(summary)) {
      description = summary.isEmpty() ? longer : summary + (summary.endsWith(".") ? " " : ". ") + longer;
    }
    ObjectNode d = JsonNodeFactory.instance.objectNode();
    d.put("opKey", opKey);
    if (operationId != null) {
      d.put("operationId", operationId);
    }
    d.put("name", name);
    d.put("title", summary.isEmpty() ? (operationId != null ? operationId : opKey) : summary);
    d.put("description", ToolText.clean(description, 900));
    d.put("method", method);
    d.put("path", path);
    d.put("nature", nature);
    ArrayNode tagArray = d.putArray("tags");
    tags.forEach(tagArray::add);
    d.set("inputSchema", input);
    d.set("params", bindings);
    if (body != null) {
      d.set("body", body);
    }
    return new Entry(opKey, method, path, operationId, summary, tags, nature, d, null);
  }

  /** The property an argument takes: the parameter's name if it is a plain one and free, else {@code query_id}. */
  private static String argName(String name, String where, Set<String> taken) {
    String word = NOT_WORD.matcher(name).replaceAll("_");
    String arg = PROPERTY.matcher(name).matches() ? name : where + "_" + word;
    if (taken.contains(arg) || arg.equals("body") && where.equals("query")) {
      arg = where + "_" + word;
    }
    String candidate = arg.length() > 64 ? arg.substring(0, 64) : arg;
    for (int i = 2; taken.contains(candidate); i++) {
      String suffix = "_" + i;
      candidate = (arg.length() > 64 - suffix.length() ? arg.substring(0, 64 - suffix.length()) : arg) + suffix;
    }
    taken.add(candidate);
    return candidate;
  }

  /**
   * {@code operationId} in snake case ({@code createRefund} → {@code create_refund}), else method and path
   * ({@code get_refunds_by_refund_id}), under the template's prefix; a name over 64 characters keeps 55 and a hash.
   */
  static String toolName(String prefix, String operationId, String method, String path) {
    String base;
    if (operationId != null && !operationId.isBlank()) {
      base = operationId;
    } else {
      base = method.toLowerCase(Locale.ROOT) + "_" + PLACEHOLDER_WORDS.matcher(path).replaceAll("by_$1");
    }
    String camel = CAMEL.matcher(ACRONYM.matcher(base).replaceAll("$1_$2")).replaceAll("$1_$2");
    String snake = EDGE_UNDERSCORES.matcher(NOT_SNAKE.matcher(camel.toLowerCase(Locale.ROOT)).replaceAll("_"))
        .replaceAll("");
    if (snake.isEmpty()) {
      snake = method.toLowerCase(Locale.ROOT);
    }
    String name = prefix == null || prefix.isEmpty() ? snake : prefix + "_" + snake;
    if (!Character.isLetter(name.charAt(0))) {
      name = "op_" + name;
    }
    if (name.length() > 64) {
      String hash = Integer.toHexString((method + " " + path).hashCode() & 0x7fffffff);
      name = name.substring(0, 55) + "_" + ("00000000" + hash).substring(hash.length());
    }
    return name;
  }

  private static String unique(String name, Set<String> names) {
    String candidate = name;
    for (int i = 2; !names.add(candidate); i++) {
      String suffix = "_" + i;
      candidate = (name.length() > 64 - suffix.length() ? name.substring(0, 64 - suffix.length()) : name) + suffix;
    }
    return candidate;
  }
}
