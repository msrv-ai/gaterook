package com.gaterook.connectors.cosmosdb;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One change of {@code cosmosdb_execute}: one operation on one item of one container, as the exact request Cosmos DB
 * gets.
 *
 * <ul>
 *   <li>create: {@code POST …/docs} with the item; refused (409) if an item with its id exists in the partition;</li>
 *   <li>upsert: the same with {@code x-ms-documentdb-is-upsert: True}: creates the item, or replaces the whole item
 *       with its id;</li>
 *   <li>replace: {@code PUT …/docs/<id>} with the whole new item; refused (404) if there is none;</li>
 *   <li>patch: {@code PATCH …/docs/<id>} with up to ten operations of Cosmos DB's partial document update ({@code add},
 *       {@code set}, {@code replace}, {@code remove}, {@code incr}, {@code move}) and an optional {@code condition};
 *       the item's id and system properties can't be patched;</li>
 *   <li>delete: {@code DELETE …/docs/<id>}.</li>
 * </ul>
 * Each carries the partition key value in {@code x-ms-documentdb-partitionkey} and, if given, {@code If-Match} with the
 * item's ETag, so it applies only to the version read. The body is the approval's text and the bytes sent alike;
 * {@link #approved} reads a payload back and accepts exactly these shapes, checked again, nothing else.
 *
 * @param body the JSON body as sent (pretty-printed), {@code null} for a delete
 */
record CosmosChange(String operation, String database, String container, String id, PartitionKey partitionKey,
    String ifMatch, String body) {

  static final Set<String> OPERATIONS = Set.of("create", "upsert", "replace", "patch", "delete");
  static final Set<String> PATCH_OPS = Set.of("add", "set", "replace", "remove", "incr", "move");
  /** Cosmos DB's limit of operations in one partial document update. */
  static final int MAX_PATCH_OPERATIONS = 10;
  static final int MAX_PATH = 1_024;
  static final int MAX_CONDITION = 4_096;
  /** System properties Cosmos DB keeps itself. */
  static final Set<String> SYSTEM = Set.of("_rid", "_self", "_etag", "_ts", "_attachments", "_lsn");
  private static final Pattern ETAG = Pattern.compile("[\\x21\\x23-\\x7E]{1,126}");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** The change the arguments of {@code cosmosdb_execute} ask for. */
  static CosmosChange of(JsonNode args, CosmosSettings s, long maxBytes) throws Refusal {
    String operation = args.path("operation").isString() ? args.get("operation").asString() : null;
    if (operation == null || !OPERATIONS.contains(operation)) {
      throw Refusal.bad("operation is create, upsert, replace, patch or delete.", null);
    }
    CosmosTarget target = CosmosTarget.of(args, s);
    String database = target.database();
    String container = target.container();
    PartitionKey pk = PartitionKey.of(args.get("partitionKey"));
    JsonNode item = args.get("item");
    boolean hasItem = item != null && !item.isNull() && !item.isMissingNode();
    JsonNode operations = args.get("operations");
    boolean hasOperations = operations != null && !operations.isNull() && !operations.isMissingNode();
    JsonNode condition = args.get("condition");
    boolean hasCondition = condition != null && !condition.isNull() && !condition.isMissingNode();
    String id = args.path("id").isString() ? args.get("id").asString() : null;
    if (args.has("id") && id == null) {
      throw Refusal.bad("id is the item's id, a string.", null);
    }
    String body;
    switch (operation) {
      case "create", "upsert", "replace" -> {
        if (hasOperations || hasCondition) {
          throw Refusal.bad(operation + " sends the whole item in item; operations and condition belong to patch.",
              null);
        }
        if (!hasItem || !item.isObject()) {
          throw Refusal.bad(operation + " needs item: the whole item as a JSON object, with its id.", null);
        }
        if (!item.path("id").isString()) {
          throw Refusal.bad("The item needs its id, a string, such as \"id\": \"o-1\".", null);
        }
        String itemId = item.get("id").asString();
        if (id != null && !id.equals(itemId)) {
          throw Refusal.bad("id and the item's own id differ: " + CosmosIds.shown(id) + " and "
              + CosmosIds.shown(itemId) + ".", null);
        }
        id = itemId;
        String problem = CosmosValues.problem(item);
        if (problem != null) {
          throw Refusal.bad("The item " + problem + ".", null);
        }
        body = CosmosHttp.PRETTY.writeValueAsString(item);
      }
      case "patch" -> {
        if (hasItem) {
          throw Refusal.bad("patch takes operations, not item: use replace to send the whole item.", null);
        }
        body = CosmosHttp.PRETTY.writeValueAsString(patchBody(operations, hasCondition ? condition : null));
      }
      default -> {
        if (hasItem || hasOperations || hasCondition) {
          throw Refusal.bad("delete takes only the id and the partition key.", null);
        }
        body = null;
      }
    }
    if (id == null) {
      throw Refusal.bad(operation + " needs id: the id of the item.", null);
    }
    String problem = CosmosIds.itemProblem(id);
    if (problem != null) {
      throw Refusal.bad("The item id " + CosmosIds.shown(id) + " " + problem + ".", null);
    }
    String etag = etag(args.get("ifMatch"), operation);
    CosmosChange change = new CosmosChange(operation, database, container, id, pk, etag, body);
    change.checkSize(s, maxBytes);
    return change;
  }

  private static ObjectNode patchBody(JsonNode operations, JsonNode condition) throws Refusal {
    if (operations == null || !operations.isArray() || operations.isEmpty()) {
      throw Refusal.bad("patch needs operations, such as [{\"op\": \"set\", \"path\": \"/status\", \"value\": "
          + "\"paid\"}].", null);
    }
    if (operations.size() > MAX_PATCH_OPERATIONS) {
      throw Refusal.bad("Cosmos DB takes at most " + MAX_PATCH_OPERATIONS + " operations in one patch.", null);
    }
    ObjectNode out = F.objectNode();
    if (condition != null) {
      if (!condition.isString() || condition.asString().length() > MAX_CONDITION
          || !condition.asString().strip().regionMatches(true, 0, "from ", 0, 5)) {
        throw Refusal.bad("condition is a filter that starts with FROM, such as \"FROM c WHERE c.status = 'open'\".",
            null);
      }
      String problem = CosmosSql.problem("SELECT * " + condition.asString());
      if (problem != null) {
        throw Refusal.bad("The condition can't be sent: " + problem, null);
      }
      out.put("condition", condition.asString());
    }
    ArrayNode ops = out.putArray("operations");
    for (int i = 0; i < operations.size(); i++) {
      ops.add(operation(operations.get(i), i + 1));
    }
    return out;
  }

  private static ObjectNode operation(JsonNode o, int n) throws Refusal {
    String at = "Operation " + n;
    if (o == null || !o.isObject()) {
      throw Refusal.bad(at + " is an object such as {\"op\": \"set\", \"path\": \"/status\", \"value\": \"paid\"}.",
          null);
    }
    for (String key : o.propertyNames()) {
      if (!Set.of("op", "path", "value", "from").contains(key)) {
        throw Refusal.bad(at + " has " + CosmosIds.shown(key) + ": an operation has op, path, and value or from.",
            null);
      }
    }
    String op = o.path("op").asString("");
    if (!PATCH_OPS.contains(op)) {
      throw Refusal.bad(at + ": op is add, set, replace, remove, incr or move.", null);
    }
    ObjectNode out = F.objectNode().put("op", op);
    out.put("path", path(o.get("path"), at + "'s path"));
    boolean needsValue = !op.equals("remove") && !op.equals("move");
    if (needsValue != o.has("value")) {
      throw Refusal.bad(at + " (" + op + ") " + (needsValue ? "needs a value." : "takes no value."), null);
    }
    if (op.equals("move") != o.has("from")) {
      throw Refusal.bad(at + ": " + (op.equals("move") ? "move needs from, the path to move." : "only move takes "
          + "from."), null);
    }
    if (op.equals("move")) {
      out.put("from", path(o.get("from"), at + "'s from"));
    }
    if (needsValue) {
      JsonNode v = o.get("value");
      if (op.equals("incr") && !v.isNumber()) {
        throw Refusal.bad(at + ": incr adds a number.", null);
      }
      String problem = CosmosValues.problem(v);
      if (problem != null) {
        throw Refusal.bad(at + "'s value " + problem + ".", null);
      }
      out.set("value", v);
    }
    return out;
  }

  /** A JSON Pointer of a patch, never the item's id or a system property. */
  private static String path(JsonNode p, String what) throws Refusal {
    if (p == null || !p.isString() || !p.asString().startsWith("/") || p.asString().length() < 2
        || p.asString().length() > MAX_PATH) {
      throw Refusal.bad(what + " is a JSON Pointer such as /status or /items/0/qty.", null);
    }
    String path = p.asString();
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        throw Refusal.bad(what + " holds a control character.", null);
      }
    }
    String first = path.substring(1).split("/", -1)[0];
    if (first.equals("id") || SYSTEM.contains(first)) {
      throw Refusal.bad(what + " " + path + " is the item's " + (first.equals("id") ? "id" : "system property")
          + ", which a patch can't change: delete the item and create it again.", null);
    }
    return path;
  }

  private static String etag(JsonNode given, String operation) throws Refusal {
    if (given == null || given.isNull() || given.isMissingNode()) {
      return null;
    }
    if (operation.equals("create")) {
      throw Refusal.bad("ifMatch applies to an existing item: create takes none.", null);
    }
    if (!given.isString()) {
      throw Refusal.bad("ifMatch is the item's _etag, as cosmosdb_read_item returns it.", null);
    }
    String t = given.asString().strip();
    String inner = t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    if (!ETAG.matcher(inner).matches()) {
      throw Refusal.bad("ifMatch is the item's _etag, as cosmosdb_read_item returns it, such as "
          + "\"\\\"00000000-0000-0000-0000-000000000000\\\"\".", null);
    }
    return "\"" + inner + "\"";
  }

  private void checkSize(CosmosSettings s, long maxRequestBytes) throws Refusal {
    if (body == null) {
      return;
    }
    long bytes = body.getBytes(StandardCharsets.UTF_8).length;
    long max = s.maxItemBytes();
    if (maxRequestBytes > 0) {
      max = Math.min(max, maxRequestBytes);
    }
    if (bytes > max) {
      throw new Refusal(Refusal.TOO_LARGE, "The change's body is " + CosmosHttp.size(bytes) + ", larger than the "
          + CosmosHttp.size(max) + " a change may send on this endpoint.", "Send a smaller item, or patch the "
          + "properties that change.");
    }
  }

  // ── The approved payload ──────────────────────────────────────────────────────────────────────────────────────

  /** What the approval stores and what runs. */
  ObjectNode payload() {
    ObjectNode p = F.objectNode().put("operation", operation).put("database", database).put("container", container)
        .put("id", id);
    p.set("partitionKey", partitionKey.values().deepCopy());
    if (ifMatch != null) {
      p.put("ifMatch", ifMatch);
    }
    if (body != null) {
      p.put("body", body);
    }
    return p;
  }

  /**
   * The approved payload read back: one of the five shapes, on an item of a container the connection allows, with a
   * body that is exactly the connector's own rendering of a body that passes the same checks again. What runs is this
   * change, so nothing the shapes don't name can ride along.
   */
  static CosmosChange approved(JsonNode p, CosmosSettings s, long maxRequestBytes) throws Refusal {
    if (p == null || !p.isObject()) {
      throw tampered();
    }
    for (String key : p.propertyNames()) {
      if (!Set.of("operation", "database", "container", "id", "partitionKey", "ifMatch", "body").contains(key)) {
        throw tampered();
      }
    }
    String operation = p.path("operation").asString("");
    if (!OPERATIONS.contains(operation) || !p.path("partitionKey").isArray() || !p.path("id").isString()
        || p.has("body") == operation.equals("delete") || (p.has("body") && !p.get("body").isString())) {
      throw tampered();
    }
    ObjectNode args = F.objectNode().put("operation", operation);
    args.set("database", p.get("database"));
    args.set("container", p.get("container"));
    args.set("partitionKey", p.get("partitionKey"));
    if (p.has("ifMatch")) {
      args.set("ifMatch", p.get("ifMatch"));
    }
    if (!operation.equals("create") && !operation.equals("upsert")) {
      args.set("id", p.get("id"));
    }
    if (p.has("body")) {
      JsonNode body;
      try {
        body = CosmosHttp.JSON.readTree(p.get("body").asString());
      } catch (JacksonException e) {
        throw tampered();
      }
      if (body == null || !body.isObject()) {
        throw tampered();
      }
      if (operation.equals("patch")) {
        for (String key : body.propertyNames()) {
          if (!key.equals("operations") && !key.equals("condition")) {
            throw tampered();
          }
        }
        args.set("operations", body.get("operations"));
        if (body.has("condition")) {
          args.set("condition", body.get("condition"));
        }
      } else {
        args.set("item", body);
      }
    }
    CosmosChange change = of(args, s, maxRequestBytes);
    PartitionKey given = PartitionKey.of(p.get("partitionKey"));
    if (!change.id.equals(p.get("id").asString()) || !change.partitionKey.sameAs(given)
        || !Objects.equals(change.ifMatch, p.path("ifMatch").asString(null))
        || !Objects.equals(change.body, p.path("body").asString(null))) {
      throw tampered();
    }
    return change;
  }

  private static Refusal tampered() {
    return Refusal.bad("The approved action isn't one change of cosmosdb_execute. Not executed.", null);
  }

  // ── The request ───────────────────────────────────────────────────────────────────────────────────────────────

  String verb() {
    return switch (operation) {
      case "create", "upsert" -> "POST";
      case "replace" -> "PUT";
      case "patch" -> "PATCH";
      default -> "DELETE";
    };
  }

  /** {@code dbs/app/colls/orders}: the container's link, as signed. */
  String containerLink() {
    return "dbs/" + database + "/colls/" + container;
  }

  /** The link the request's signature covers: the container's for create and upsert, else the item's. */
  String resourceLink() {
    return operation.equals("create") || operation.equals("upsert") ? containerLink() : containerLink() + "/docs/"
        + id;
  }

  /** The encoded path. */
  String path() {
    String docs = "/dbs/" + CosmosIds.encode(database) + "/colls/" + CosmosIds.encode(container) + "/docs";
    return operation.equals("create") || operation.equals("upsert") ? docs : docs + "/" + CosmosIds.encode(id);
  }

  /** Cosmos DB's headers of the change, in the order shown. */
  Map<String, String> headers() {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("x-ms-documentdb-partitionkey", partitionKey.header());
    if (operation.equals("upsert")) {
      h.put("x-ms-documentdb-is-upsert", "True");
    }
    if (ifMatch != null) {
      h.put("If-Match", ifMatch);
    }
    return h;
  }

  String contentType() {
    return operation.equals("patch") ? "application/json_patch+json" : "application/json";
  }

  byte[] bytes() {
    return body == null ? null : body.getBytes(StandardCharsets.UTF_8);
  }

  /** The request as the approver sees it: method, URL, Cosmos DB's headers and the body, byte for byte. */
  String text(CosmosEndpoint endpoint) {
    StringBuilder out = new StringBuilder(verb()).append(' ').append(endpoint.base()).append(path()).append('\n');
    for (Map.Entry<String, String> h : headers().entrySet()) {
      out.append(h.getKey()).append(": ").append(h.getValue()).append('\n');
    }
    if (body != null) {
      out.append("Content-Type: ").append(contentType()).append("\n\n").append(body);
    }
    return out.toString();
  }

  /** Whether the body holds an integer Cosmos DB won't store exactly. */
  boolean inexactNumbers() {
    if (body == null) {
      return false;
    }
    try {
      return CosmosValues.inexact(CosmosHttp.JSON.readTree(body));
    } catch (JacksonException e) {
      return false;
    }
  }
}
