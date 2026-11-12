package ai.msrv.gaterook.connectors.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The one filter language of the vector_* and search_* tools: a subset of MongoDB's query documents that every store
 * here can evaluate on its own side. Agents write {@code {"genre": "drama", "year": {"$gte": 2020}}}; each backend
 * renders the tree in its own syntax (Qdrant's {@code must}, a Milvus expression, Algolia's {@code filters}, OData…)
 * with its own quoting, so no text of an agent ever becomes a query language.
 *
 * <ul>
 *   <li>A field with a value is {@code $eq}; with an object, its operators: {@code $eq $ne $gt $gte $lt $lte} with a
 *       string, an integer, a number or a boolean; {@code $in} and {@code $nin} with a list of one type;
 *       {@code $exists} with a boolean.</li>
 *   <li>{@code $and} and {@code $or} take a list of filters; several keys of one object are all required.</li>
 *   <li>At most {@link #MAX_DEPTH} levels, {@link #MAX_NODES} conditions, {@link #MAX_VALUES} values in a list; no
 *       {@code null}: {@code {"$exists": false}} says a field is missing.</li>
 * </ul>
 */
final class Filter {

  static final int MAX_DEPTH = 6;
  static final int MAX_NODES = 64;
  static final int MAX_VALUES = 100;
  static final int MAX_TEXT = 1_024;
  static final int MAX_FIELD = 255;
  private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]");

  private Filter() {}

  /** A filter tree. */
  sealed interface Node permits And, Or, Cmp, In, Exists {}

  record And(List<Node> nodes) implements Node {}

  record Or(List<Node> nodes) implements Node {}

  /** A comparison. */
  record Cmp(String field, Op op, Value value) implements Node {}

  /** {@code $in}, or {@code $nin} when {@code not}. */
  record In(String field, List<Value> values, boolean not) implements Node {}

  record Exists(String field, boolean exists) implements Node {}

  enum Op {
    EQ("$eq"), NE("$ne"), GT("$gt"), GTE("$gte"), LT("$lt"), LTE("$lte");

    final String name;

    Op(String name) {
      this.name = name;
    }

    boolean range() {
      return this != EQ && this != NE;
    }
  }

  /** A value: text, an integer, a number or a boolean. */
  sealed interface Value permits Text, Int, Num, Bool {
    /** The value as JSON reads it. */
    JsonNode json();

    String kind();
  }

  record Text(String value) implements Value {
    public JsonNode json() {
      return ServiceHttp.JSON.getNodeFactory().stringNode(value);
    }

    public String kind() {
      return "text";
    }
  }

  record Int(long value) implements Value {
    public JsonNode json() {
      return ServiceHttp.JSON.getNodeFactory().numberNode(value);
    }

    public String kind() {
      return "integer";
    }
  }

  record Num(double value) implements Value {
    public JsonNode json() {
      return ServiceHttp.JSON.getNodeFactory().numberNode(value);
    }

    public String kind() {
      return "number";
    }
  }

  record Bool(boolean value) implements Value {
    public JsonNode json() {
      return ServiceHttp.JSON.getNodeFactory().booleanNode(value);
    }

    public String kind() {
      return "boolean";
    }
  }

  /** Why a filter can't be read, in a sentence for the agent. */
  static final class Invalid extends Exception {
    Invalid(String message) {
      super(message, null, false, false);
    }
  }

  /** The tree of {@code filter}, {@code null} for none (missing, {@code null} or {@code {}}). */
  static Node parse(JsonNode filter) throws Invalid {
    if (filter == null || filter.isNull() || filter.isMissingNode() || (filter.isObject() && filter.isEmpty())) {
      return null;
    }
    int[] count = {0};
    return object(filter, 1, count);
  }

  private static Node object(JsonNode node, int depth, int[] count) throws Invalid {
    if (!node.isObject()) {
      throw new Invalid("A filter is an object, such as {\"genre\": \"drama\", \"year\": {\"$gte\": 2020}}.");
    }
    if (depth > MAX_DEPTH) {
      throw new Invalid("A filter nests at most " + MAX_DEPTH + " levels.");
    }
    if (node.isEmpty()) {
      throw new Invalid("A filter inside $and or $or can't be empty.");
    }
    List<Node> all = new ArrayList<>();
    for (Map.Entry<String, JsonNode> e : node.properties()) {
      String key = e.getKey();
      if (key.equals("$and") || key.equals("$or")) {
        JsonNode list = e.getValue();
        if (!list.isArray() || list.isEmpty()) {
          throw new Invalid(key + " takes a list of filters.");
        }
        List<Node> parts = new ArrayList<>();
        for (JsonNode part : list) {
          parts.add(object(part, depth + 1, count));
        }
        all.add(key.equals("$and") ? and(parts) : parts.size() == 1 ? parts.getFirst() : new Or(List.copyOf(parts)));
      } else if (key.startsWith("$")) {
        throw new Invalid("Unknown filter operator " + key + ": use $and, $or, or a field with $eq, $ne, $gt, "
            + "$gte, $lt, $lte, $in, $nin or $exists.");
      } else {
        field(key);
        all.addAll(conditions(key, e.getValue(), count));
      }
    }
    return and(all);
  }

  private static Node and(List<Node> nodes) {
    return nodes.size() == 1 ? nodes.getFirst() : new And(List.copyOf(nodes));
  }

  private static List<Node> conditions(String field, JsonNode value, int[] count) throws Invalid {
    List<Node> out = new ArrayList<>();
    if (!value.isObject()) {
      out.add(counted(new Cmp(field, Op.EQ, value(field, value)), count));
      return out;
    }
    if (value.isEmpty()) {
      throw new Invalid("The condition of " + field + " is empty.");
    }
    for (Map.Entry<String, JsonNode> e : value.properties()) {
      String op = e.getKey();
      JsonNode v = e.getValue();
      switch (op) {
        case "$in", "$nin" -> {
          if (!v.isArray() || v.isEmpty()) {
            throw new Invalid(op + " of " + field + " takes a list of values.");
          }
          if (v.size() > MAX_VALUES) {
            throw new Invalid(op + " of " + field + " takes at most " + MAX_VALUES + " values.");
          }
          List<Value> values = new ArrayList<>();
          for (JsonNode item : v) {
            Value one = value(field, item);
            if (!values.isEmpty() && !values.getFirst().kind().equals(one.kind())
                && !(numeric(values.getFirst()) && numeric(one))) {
              throw new Invalid("The values of " + op + " of " + field + " are all of one type.");
            }
            values.add(one);
          }
          out.add(counted(new In(field, List.copyOf(values), op.equals("$nin")), count));
        }
        case "$exists" -> {
          if (!v.isBoolean()) {
            throw new Invalid("$exists of " + field + " is true or false.");
          }
          out.add(counted(new Exists(field, v.asBoolean()), count));
        }
        default -> {
          Op found = null;
          for (Op o : Op.values()) {
            if (o.name.equals(op)) {
              found = o;
            }
          }
          if (found == null) {
            throw new Invalid("Unknown operator " + op + " for " + field + ": use $eq, $ne, $gt, $gte, $lt, $lte, "
                + "$in, $nin or $exists.");
          }
          Value val = value(field, v);
          if (found.range() && val instanceof Bool) {
            throw new Invalid(op + " of " + field + " compares numbers or text, not true or false.");
          }
          out.add(counted(new Cmp(field, found, val), count));
        }
      }
    }
    return out;
  }

  private static boolean numeric(Value v) {
    return v instanceof Int || v instanceof Num;
  }

  private static Node counted(Node n, int[] count) throws Invalid {
    if (++count[0] > MAX_NODES) {
      throw new Invalid("A filter has at most " + MAX_NODES + " conditions.");
    }
    return n;
  }

  private static void field(String name) throws Invalid {
    if (name.isEmpty() || name.length() > MAX_FIELD || CONTROL.matcher(name).find()) {
      throw new Invalid("A field name in a filter has 1 to " + MAX_FIELD + " characters, without control "
          + "characters.");
    }
  }

  private static Value value(String field, JsonNode v) throws Invalid {
    if (v.isString()) {
      String s = v.asString();
      if (s.length() > MAX_TEXT) {
        throw new Invalid("A text value of " + field + " has at most " + MAX_TEXT + " characters.");
      }
      if (CONTROL.matcher(s).find()) {
        throw new Invalid("A text value of " + field + " can't contain control characters.");
      }
      return new Text(s);
    }
    if (v.isBoolean()) {
      return new Bool(v.asBoolean());
    }
    if (v.isIntegralNumber()) {
      if (!v.canConvertToLong()) {
        throw new Invalid("The integer of " + field + " is too large.");
      }
      return new Int(v.asLong());
    }
    if (v.isNumber()) {
      double d = v.asDouble();
      if (!Double.isFinite(d)) {
        throw new Invalid("The number of " + field + " must be finite.");
      }
      return new Num(d);
    }
    if (v.isNull()) {
      throw new Invalid("A filter has no null: {\"" + field + "\": {\"$exists\": false}} matches a missing field.");
    }
    throw new Invalid("The value of " + field + " is text, a number or true or false; a list goes with $in.");
  }

  /** Every field the tree names, in order, each once. */
  static List<String> fields(Node node) {
    List<String> out = new ArrayList<>();
    collect(node, out);
    return out;
  }

  private static void collect(Node node, List<String> out) {
    switch (node) {
      case null -> {}
      case And a -> a.nodes().forEach(n -> collect(n, out));
      case Or o -> o.nodes().forEach(n -> collect(n, out));
      case Cmp c -> add(out, c.field());
      case In i -> add(out, i.field());
      case Exists e -> add(out, e.field());
    }
  }

  private static void add(List<String> out, String field) {
    if (!out.contains(field)) {
      out.add(field);
    }
  }

  /** Checks every field of {@code node} against {@code pattern}; {@code what} says what the names may be. */
  static void checkFields(Node node, Pattern pattern, String what) throws Invalid {
    for (String f : fields(node)) {
      if (!pattern.matcher(f).matches()) {
        throw new Invalid("The field " + f + " can't be used in a filter here: " + what);
      }
    }
  }

  /** A number as text without a needless exponent: 2020, 0.5, 1.0E-7. */
  static String number(Value v) {
    return switch (v) {
      case Int i -> Long.toString(i.value());
      case Num n -> {
        double d = n.value();
        yield d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) + ".0" : Double.toString(d);
      }
      default -> throw new IllegalArgumentException("not a number");
    };
  }

  /** The filter's JSON in MongoDB's form, normalized: one operator per object, $and around several. */
  static JsonNode mongo(Node node, boolean allowExists) throws Invalid {
    var f = ServiceHttp.JSON.getNodeFactory();
    return switch (node) {
      case And a -> {
        var list = f.arrayNode();
        for (Node n : a.nodes()) {
          list.add(mongo(n, allowExists));
        }
        yield f.objectNode().set("$and", list);
      }
      case Or o -> {
        var list = f.arrayNode();
        for (Node n : o.nodes()) {
          list.add(mongo(n, allowExists));
        }
        yield f.objectNode().set("$or", list);
      }
      case Cmp c -> f.objectNode().set(c.field(), f.objectNode().set(c.op().name, c.value().json()));
      case In i -> {
        var list = f.arrayNode();
        i.values().forEach(v -> list.add(v.json()));
        yield f.objectNode().set(i.field(), f.objectNode().set(i.not() ? "$nin" : "$in", list));
      }
      case Exists e -> {
        if (!allowExists) {
          throw new Invalid("$exists isn't supported by this store's filters.");
        }
        yield f.objectNode().set(e.field(), f.objectNode().put("$exists", e.exists()));
      }
    };
  }
}
