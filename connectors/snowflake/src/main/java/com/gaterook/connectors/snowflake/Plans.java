package com.gaterook.connectors.snowflake;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code EXPLAIN USING JSON} says Snowflake would do: {@code {"GlobalStats": {…}, "Operations": [[{"id": 0,
 * "operation": "Result", …}, {"operation": "TableScan", "objects": ["DB.SCHEMA.TABLE"], …}]]}}. Snowflake compiles
 * the statement without running it, and names every table it would read with its full name. A read runs only after
 * its plan was read, holds no operation that changes data, and, with a table list, names only listed tables.
 */
final class Plans {

  /** Words of operations that change data or objects: an operation whose name holds one is refused in a read. */
  static final List<String> CHANGING = List.of("insert", "update", "delete", "merge", "unload", "copy", "create",
      "drop", "alter", "truncate", "grant", "revoke");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_OPERATIONS = 100_000;

  private Plans() {}

  /**
   * A plan read.
   *
   * @param operations the name of every operation
   * @param objects    every object an operation names, as Snowflake wrote it
   * @param problem    why the plan can't be read; {@code null} when it can
   */
  record Plan(List<String> operations, List<String> objects, String problem) {

    boolean readable() {
      return problem == null;
    }

    /** The operations that would change data. */
    List<String> changing() {
      List<String> out = new ArrayList<>();
      for (String op : operations) {
        String lower = op.toLowerCase(Locale.ROOT);
        if (CHANGING.stream().anyMatch(lower::contains) && !out.contains(op)) {
          out.add(op);
        }
      }
      return out;
    }
  }

  static Plan read(String json) {
    if (json == null || json.isBlank()) {
      return unreadable("Snowflake returned no plan.");
    }
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (JacksonException e) {
      return unreadable("Snowflake's plan isn't JSON.");
    }
    JsonNode steps = root == null ? null : root.get("Operations");
    if (steps == null || !steps.isArray() || steps.isEmpty()) {
      return unreadable("Snowflake's plan has no operations.");
    }
    List<String> operations = new ArrayList<>();
    List<String> objects = new ArrayList<>();
    for (JsonNode step : steps) {
      for (JsonNode op : step.isArray() ? step : List.of(step)) {
        if (!op.isObject() || !op.path("operation").isString() || op.get("operation").asString().isBlank()) {
          return unreadable("An operation of Snowflake's plan has no name.");
        }
        operations.add(op.get("operation").asString());
        JsonNode named = op.path("objects");
        if (!named.isMissingNode() && !named.isNull()) {
          if (!named.isArray()) {
            return unreadable("Snowflake's plan names its objects in a way GateRook doesn't read.");
          }
          for (JsonNode o : named) {
            if (!o.isString()) {
              return unreadable("Snowflake's plan names its objects in a way GateRook doesn't read.");
            }
            objects.add(o.asString());
          }
        }
        if (operations.size() > MAX_OPERATIONS) {
          return unreadable("Snowflake's plan is too large to read.");
        }
      }
    }
    if (operations.isEmpty()) {
      return unreadable("Snowflake's plan has no operations.");
    }
    return new Plan(List.copyOf(operations), List.copyOf(objects), null);
  }

  private static Plan unreadable(String problem) {
    return new Plan(List.of(), List.of(), problem);
  }
}
