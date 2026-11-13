package com.gaterook.connectors.search;

import com.gaterook.connector.api.Classification;
import com.gaterook.connector.api.ConnectorException;
import com.gaterook.connector.api.PolicyView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The arguments of a vector_* or search_* call, read the same way before the policy ({@code classify}, a refusal is a
 * denial of rule {@link Refused#rule}) and when it runs ({@code execute}, a refusal is bad input): the core never runs
 * what the classification didn't read.
 */
final class Args {

  private Args() {}

  /** Why the arguments can't run. */
  static final class Refused extends Exception {
    final String rule;
    final String hint;

    Refused(String rule, String reason, String hint) {
      super(reason, null, false, false);
      this.rule = rule;
      this.hint = hint;
    }

    Classification denial(PolicyView policy) {
      return Classification.deny(policy.rule(rule), getMessage(), hint);
    }

    ConnectorException exception() {
      return "not-listed".equals(rule) ? ConnectorException.denied(getMessage())
          : ConnectorException.badInput(getMessage() + (hint == null ? "" : " " + hint));
    }
  }

  static String text(JsonNode args, String field) {
    JsonNode v = args == null ? null : args.get(field);
    return v == null || !v.isString() ? null : v.asString();
  }

  static boolean flag(JsonNode args, String field) {
    JsonNode v = args == null ? null : args.get(field);
    return v != null && v.isBoolean() && v.asBoolean();
  }

  /** An integer argument within {@code [min, max]}, {@code dflt} when missing. */
  static int number(JsonNode args, String field, int min, int max, int dflt) throws Refused {
    JsonNode v = args == null ? null : args.get(field);
    if (v == null || v.isNull()) {
      return dflt;
    }
    if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < min || v.asInt() > max) {
      throw new Refused("bad-argument", field + " is a whole number from " + min + " to " + max + ".", null);
    }
    return v.asInt();
  }

  /**
   * The collection or index a call names, checked against the store's name rules and the connection's list.
   *
   * @param noun "collection" or "index"
   */
  static String target(JsonNode args, String field, StoreBackend backend, com.gaterook.connector.api
      .ConnectionConfig cfg, String noun, String listTool) throws Refused {
    String name = text(args, field);
    if (name == null || name.isBlank()) {
      throw new Refused("bad-argument", "Name the " + noun + ".", "Call " + listTool + " to see them.");
    }
    String problem = backend.nameProblem(name);
    if (problem != null) {
      throw new Refused("bad-argument", problem, "Call " + listTool + " to see them.");
    }
    if (!backend.allows(cfg, name)) {
      throw new Refused("not-listed", name + " isn't one of the " + (noun.equals("index") ? "indexes" : noun + "s")
          + " of " + cfg.name() + ".", "Call " + listTool + " to see the ones agents may use.");
    }
    return name;
  }

  /** The ids of {@code field}: each once, each valid for the store. */
  static List<JsonNode> ids(JsonNode args, String field, int max, java.util.function.Function<JsonNode, String> check)
      throws Refused {
    JsonNode list = args == null ? null : args.get(field);
    if (list == null || list.isNull()) {
      return List.of();
    }
    if (!list.isArray() || list.isEmpty()) {
      throw new Refused("bad-argument", field + " is a list of ids.", null);
    }
    if (list.size() > max) {
      throw new Refused("too-many", "At most " + max + " ids per call; this one has " + list.size() + ".",
          "Split them into several calls.");
    }
    List<JsonNode> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (JsonNode id : list) {
      String problem = idText(id);
      if (problem == null) {
        problem = check.apply(id);
      }
      if (problem != null) {
        throw new Refused("bad-argument", problem, null);
      }
      if (!seen.add(id.toString())) {
        throw new Refused("bad-argument", "The id " + id + " is in " + field + " twice.", null);
      }
      out.add(id);
    }
    return out;
  }

  /** Why {@code id} isn't an id at all: ids are text or integers. */
  static String idText(JsonNode id) {
    if (id == null || !(id.isString() || id.isIntegralNumber())) {
      return "An id is text or an integer.";
    }
    if (id.isIntegralNumber() && !id.canConvertToLong()) {
      return "The id " + id + " is too large.";
    }
    if (id.isString() && (id.asString().isEmpty() || id.asString().length() > 512
        || id.asString().chars().anyMatch(c -> c < 0x20 || c == 0x7f))) {
      return "An id has 1 to 512 characters, without control characters.";
    }
    return null;
  }

  /** The filter of a call, read and checked for the store; {@code null}: none. */
  static Filter.Node filter(JsonNode args, ThrowingCheck check) throws Refused {
    try {
      Filter.Node node = Filter.parse(args == null ? null : args.get("filter"));
      if (node != null) {
        check.check(node);
      }
      return node;
    } catch (Filter.Invalid e) {
      throw new Refused("bad-filter", e.getMessage(), null);
    }
  }

  /** A store's check of a filter's fields. */
  @FunctionalInterface
  interface ThrowingCheck {
    void check(Filter.Node node) throws Filter.Invalid;
  }

  /** A vector: finite numbers, at most 20,000. */
  static List<Double> vector(JsonNode v, String what) throws Refused {
    if (v == null || !v.isArray() || v.isEmpty() || v.size() > 20_000) {
      throw new Refused("bad-argument", what + " is a list of 1 to 20,000 numbers.", null);
    }
    List<Double> out = new ArrayList<>(v.size());
    for (JsonNode n : v) {
      if (!n.isNumber() || !Double.isFinite(n.asDouble())) {
        throw new Refused("bad-argument", what + " holds only finite numbers.", null);
      }
      out.add(n.asDouble());
    }
    return out;
  }
}
