package com.gaterook.connectors.firebase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One write of {@code rtdb_write}: exactly one request at one path, {@code PUT} for set (the data there replaced),
 * {@code PATCH} for update (the children named changed, a key may be a path further down, {@code null} removes one) or
 * {@code DELETE}, each with {@code print=silent}. Neither a set nor a delete reaches the root, and every path written
 * — each key of an update — must start with a top-level key on the connection's list.
 *
 * <p>The payload of the approval, {@link #payload}, is what runs: {@link #approved} reads it back through the same
 * checks.
 */
record RtdbWrite(String operation, RtdbPath path, JsonNode value) {

  static final Set<String> OPERATIONS = Set.of("set", "update", "delete");
  /** Keys of a value that the database gives a meaning: server values and priorities. */
  static final Set<String> SPECIAL = Set.of(".sv", ".priority", ".value");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** The write the arguments of {@code rtdb_write} ask for. */
  static RtdbWrite of(JsonNode args, RtdbSettings s, long maxBytes) throws Refusal {
    String operation = args.path("operation").isString() ? args.get("operation").asString() : null;
    if (operation == null || !OPERATIONS.contains(operation)) {
      throw Refusal.bad("operation must be set, update or delete.", null);
    }
    JsonNode p = args.path("path");
    if (!p.isString()) {
      throw Refusal.bad("path must be a path such as /users/alice.", null);
    }
    RtdbPath path = RtdbPath.parse(p.asString());
    JsonNode value = args.get("value");
    boolean hasValue = value != null && !value.isMissingNode();
    if (path.root() && !operation.equals("update")) {
      throw new Refusal(Refusal.ROOT_WRITE, "rtdb_write doesn't " + (operation.equals("set") ? "replace" : "delete")
          + " the whole database.", "Name a path below the root, such as /users/alice.");
    }
    switch (operation) {
      case "delete" -> {
        if (hasValue) {
          throw Refusal.bad("delete takes no value.", null);
        }
        value = null;
      }
      case "set" -> {
        if (!hasValue || value.isNull()) {
          throw Refusal.bad("set needs a value; null would delete the data.", "Use operation delete to remove it.");
        }
        check(value, path.depth(), "value");
      }
      default -> {
        if (!hasValue || !value.isObject() || value.isEmpty()) {
          throw Refusal.bad("update needs an object of children to change, such as {\"name\": \"Alice\", "
              + "\"profile/city\": \"Berlin\"}.", null);
        }
        for (Map.Entry<String, JsonNode> e : value.properties()) {
          RtdbPath child = RtdbPath.relative(e.getKey());
          if (path.depth() + child.depth() > RtdbPath.MAX_DEPTH) {
            throw Refusal.bad("The child " + Names.shown(e.getKey()) + " is deeper than the database's "
                + RtdbPath.MAX_DEPTH + " levels.", null);
          }
          check(e.getValue(), path.depth() + child.depth(), "value." + e.getKey());
        }
      }
    }
    RtdbWrite w = new RtdbWrite(operation, path, value == null ? null : value.deepCopy());
    for (RtdbPath target : w.targets()) {
      if (!target.root() && !s.allows(target.top())) {
        throw new Refusal(Refusal.NOT_ALLOWED_PATH, "/" + Names.shown(target.top()) + " isn't one of the paths agents "
            + "may use on this connection.", "Call rtdb_get with path / to see the paths you can use.");
      }
    }
    byte[] body = w.body();
    if (maxBytes > 0 && body != null && body.length > maxBytes) {
      throw new Refusal(Refusal.TOO_LARGE, "The value is " + GoogleHttp.size(body.length) + ", larger than the "
          + GoogleHttp.size(maxBytes) + " a request may be on this endpoint.", "Write less at a time.");
    }
    return w;
  }

  /** The approved payload, read back through the checks of {@link #of}: nothing else may run. */
  static RtdbWrite approved(JsonNode payload, RtdbSettings s) throws Refusal {
    if (payload == null || !payload.isObject()) {
      throw tampered();
    }
    for (String key : payload.propertyNames()) {
      if (!Set.of("operation", "path", "value").contains(key)) {
        throw tampered();
      }
    }
    RtdbWrite w = of(payload, s, 0);
    if (!w.payload().equals(payload)) {
      throw tampered();
    }
    return w;
  }

  private static Refusal tampered() {
    return Refusal.bad("The approved action isn't one write of rtdb_write. Not executed.", null);
  }

  /** The keys inside a value are the database's: no slash, dot (but its own .sv, .priority, .value) or $ # [ ]. */
  private static void check(JsonNode v, int depth, String where) throws Refusal {
    if (depth > RtdbPath.MAX_DEPTH) {
      throw Refusal.bad(where + " nests deeper than the database's " + RtdbPath.MAX_DEPTH + " levels.", null);
    }
    if (v.isObject()) {
      for (Map.Entry<String, JsonNode> e : v.properties()) {
        String problem = SPECIAL.contains(e.getKey()) ? null : RtdbPath.problem(e.getKey());
        if (problem != null) {
          throw Refusal.bad("The key " + Names.shown(e.getKey()) + " in " + where + " " + problem + ".",
              "A key may not hold . $ # [ ] / ? % \\ or control characters.");
        }
        check(e.getValue(), depth + 1, where + "." + e.getKey());
      }
    } else if (v.isArray()) {
      int i = 0;
      for (JsonNode e : v) {
        check(e, depth + 1, where + "[" + i++ + "]");
      }
    } else if (v.isNumber() && !Double.isFinite(v.doubleValue())) {
      throw Refusal.bad(where + " isn't a finite number.", null);
    }
  }

  /** Every path the write changes: the path itself, or each child an update names. */
  List<RtdbPath> targets() throws Refusal {
    if (!operation.equals("update")) {
      return List.of(path);
    }
    List<RtdbPath> out = new ArrayList<>();
    for (String key : value.propertyNames()) {
      out.add(path.resolve(RtdbPath.relative(key)));
    }
    return out;
  }

  String method() {
    return switch (operation) {
      case "set" -> "PUT";
      case "update" -> "PATCH";
      default -> "DELETE";
    };
  }

  /** The request's body, the approval's text and the wire alike; {@code null} for a delete. */
  byte[] body() {
    return value == null ? null
        : GoogleHttp.PRETTY.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
  }

  /** The approval's payload: {@code {"operation", "path", "value"}}. */
  ObjectNode payload() {
    ObjectNode out = F.objectNode().put("operation", operation).put("path", path.text());
    if (value != null) {
      out.set("value", value.deepCopy());
    }
    return out;
  }
}
