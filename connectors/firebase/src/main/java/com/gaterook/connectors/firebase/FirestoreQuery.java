package com.gaterook.connectors.firebase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The arguments of {@code firestore_query} as Firestore's structured query, checked without I/O: one collection by its
 * path or one collection group by its ID, both on the connection's list; filters on field paths that all must hold,
 * {@code == null} and {@code == NaN} as Firestore's unary filters; an ordering.
 *
 * <p>The ordering is sent whole, as Firestore would complete it ({@code StructuredQuery.order_by}): the agent's fields,
 * then the fields of inequality filters not among them (by name), then {@code __name__}, each added one in the
 * direction of the last. A page then starts after the order values of the last document shown, which name one place in
 * a total order: no document is skipped or shown twice between pages.
 */
record FirestoreQuery(String parent, FirestorePath collection, String group, ObjectNode structuredQuery,
    List<Order> order) {

  /** One field of the ordering: its canonical path, its names, its direction. */
  record Order(String field, List<String> names, boolean descending) {}

  /** The agent's operators and Firestore's. */
  static final Map<String, String> OPS = Map.of("==", "EQUAL", "!=", "NOT_EQUAL", "<", "LESS_THAN",
      "<=", "LESS_THAN_OR_EQUAL", ">", "GREATER_THAN", ">=", "GREATER_THAN_OR_EQUAL", "array-contains",
      "ARRAY_CONTAINS", "array-contains-any", "ARRAY_CONTAINS_ANY", "in", "IN", "not-in", "NOT_IN");
  /** Operators that take a list. */
  static final Set<String> LIST_OPS = Set.of("in", "not-in", "array-contains-any");
  /** Firestore's inequalities: their fields join the ordering. */
  static final Set<String> INEQUALITIES = Set.of("!=", "<", "<=", ">", ">=", "not-in");
  /** Firestore takes up to 30 values in a list filter. */
  static final int MAX_LIST = 30;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  FirestoreQuery {
    order = List.copyOf(order);
  }

  static FirestoreQuery of(JsonNode args, FirestoreSettings s) throws Refusal {
    JsonNode c = args.get("collection");
    JsonNode g = args.get("collectionGroup");
    boolean hasCollection = c != null && !c.isNull();
    boolean hasGroup = g != null && !g.isNull();
    if (hasCollection == hasGroup) {
      throw Refusal.bad("Name either collection or collectionGroup.", "collection is a path such as users or "
          + "users/alice/orders; collectionGroup an ID such as orders, for every collection with it.");
    }
    FirestorePath collection = null;
    String group = null;
    String parent;
    ObjectNode from = F.objectNode();
    if (hasCollection) {
      if (!c.isString()) {
        throw Refusal.bad("collection must be a collection's path, such as users.", null);
      }
      collection = FirestorePath.parse(c.asString());
      if (!collection.collection()) {
        throw Refusal.bad("collection " + Names.shown(c.asString()) + " isn't a collection: a collection's path has "
            + "an odd number of IDs, such as users or users/alice/orders.", "Read one document with "
            + "firestore_get_document.");
      }
      if (!s.allowsCollection(collection)) {
        throw notAllowed(collection.text());
      }
      parent = collection.parent().name(s.documents());
      from.put("collectionId", collection.id()).put("allDescendants", false);
    } else {
      if (!g.isString()) {
        throw Refusal.bad("collectionGroup must be a collection ID, such as orders.", null);
      }
      group = g.asString();
      String problem = FirestorePath.problem(group);
      if (problem != null) {
        throw Refusal.bad("The collection ID " + Names.shown(group) + " " + problem + ".", null);
      }
      if (!s.allowsGroup(group)) {
        throw new Refusal(Refusal.NOT_ALLOWED_COLLECTION, "A query of every collection " + Names.shown(group)
            + " isn't allowed on this connection.", "Query one collection by its path, or call "
            + "firestore_list_collections to see the collections you can use.");
      }
      parent = s.documents();
      from.put("collectionId", group).put("allDescendants", true);
    }
    ObjectNode query = F.objectNode();
    query.putArray("from").add(from);

    JsonNode where = args.path("where");
    List<ObjectNode> filters = new ArrayList<>();
    Set<String> inequalities = new TreeSet<>();
    if (!where.isMissingNode() && !where.isNull()) {
      if (!where.isArray()) {
        throw Refusal.bad("where must be a list of filters such as {\"field\": \"status\", \"op\": \"==\", "
            + "\"value\": \"open\"}.", null);
      }
      int i = 0;
      for (JsonNode w : where) {
        filters.add(filter(w, s, collection, i++, inequalities));
      }
    }
    if (filters.size() == 1) {
      query.set("where", filters.getFirst());
    } else if (filters.size() > 1) {
      ObjectNode composite = query.putObject("where").putObject("compositeFilter").put("op", "AND");
      ArrayNode list = composite.putArray("filters");
      filters.forEach(list::add);
    }

    List<Order> order = new ArrayList<>();
    Set<String> ordered = new HashSet<>();
    JsonNode orderBy = args.path("orderBy");
    if (!orderBy.isMissingNode() && !orderBy.isNull()) {
      if (!orderBy.isArray()) {
        throw Refusal.bad("orderBy must be a list such as [{\"field\": \"created\", \"direction\": \"desc\"}].", null);
      }
      int i = 0;
      for (JsonNode o : orderBy) {
        String at = "orderBy[" + i++ + "]";
        if (!o.isObject() || !o.path("field").isString()) {
          throw Refusal.bad(at + " needs a field.", null);
        }
        List<String> names = FieldPaths.segments(o.get("field").asString());
        String field = FieldPaths.join(names);
        String direction = o.path("direction").isMissingNode() || o.path("direction").isNull() ? "asc"
            : o.path("direction").asString("");
        if (!direction.equals("asc") && !direction.equals("desc")) {
          throw Refusal.bad(at + ".direction must be asc or desc.", null);
        }
        if (!ordered.add(field)) {
          throw Refusal.bad("orderBy names " + Names.shown(field) + " twice.", null);
        }
        order.add(new Order(field, names, direction.equals("desc")));
      }
    }
    boolean descending = !order.isEmpty() && order.getLast().descending();
    for (String field : inequalities) {
      if (ordered.add(field)) {
        order.add(new Order(field, FieldPaths.segments(field), descending));
      }
    }
    if (ordered.add(FieldPaths.NAME)) {
      order.add(new Order(FieldPaths.NAME, List.of(FieldPaths.NAME), descending));
    }
    ArrayNode orders = query.putArray("orderBy");
    for (Order o : order) {
      ObjectNode n = orders.addObject();
      n.putObject("field").put("fieldPath", o.field());
      n.put("direction", o.descending() ? "DESCENDING" : "ASCENDING");
    }
    return new FirestoreQuery(parent, collection, group, query, order);
  }

  private static Refusal notAllowed(String collection) {
    return new Refusal(Refusal.NOT_ALLOWED_COLLECTION, Names.shown(collection) + " isn't one of the collections "
        + "agents may use on this connection.", "Call firestore_list_collections to see the collections you can use.");
  }

  private static ObjectNode filter(JsonNode w, FirestoreSettings s, FirestorePath collection, int i,
      Set<String> inequalities) throws Refusal {
    String at = "where[" + i + "]";
    if (!w.isObject() || !w.path("field").isString() || !w.path("op").isString()) {
      throw Refusal.bad(at + " needs a field and an op, such as {\"field\": \"status\", \"op\": \"==\", \"value\": "
          + "\"open\"}.", null);
    }
    String field = FieldPaths.canonical(w.get("field").asString());
    String op = w.get("op").asString();
    String fsOp = OPS.get(op);
    if (fsOp == null) {
      throw Refusal.bad(at + ".op must be one of ==, !=, <, <=, >, >=, array-contains, array-contains-any, in or "
          + "not-in.", null);
    }
    if (!w.has("value")) {
      throw Refusal.bad(at + " needs a value; null compares with null.", null);
    }
    JsonNode raw = w.get("value");
    boolean name = field.equals(FieldPaths.NAME);
    ObjectNode value;
    if (LIST_OPS.contains(op)) {
      JsonNode elements = raw.isArray() ? raw
          : raw.isObject() && raw.size() == 1 && raw.has("arrayValue") ? raw.path("arrayValue").path("values") : null;
      if (elements == null || !elements.isArray() || elements.isEmpty() || elements.size() > MAX_LIST) {
        throw Refusal.bad(at + ": " + op + " takes a list of 1 to " + MAX_LIST + " values.", null);
      }
      ArrayNode values = F.arrayNode();
      int j = 0;
      for (JsonNode e : elements) {
        values.add(name ? reference(e, s, collection, at + ".value[" + j + "]")
            : FirestoreValues.typed(e, s.documents(), at + ".value[" + j + "]"));
        j++;
      }
      value = F.objectNode();
      value.putObject("arrayValue").set("values", values);
    } else {
      value = name ? reference(raw, s, collection, at + ".value") : FirestoreValues.typed(raw, s.documents(),
          at + ".value");
    }
    if (INEQUALITIES.contains(op)) {
      inequalities.add(field);
    }
    String unary = null;
    if (value.has("nullValue")) {
      unary = op.equals("==") ? "IS_NULL" : op.equals("!=") ? "IS_NOT_NULL" : "";
    } else if (value.has("doubleValue") && "NaN".equals(value.get("doubleValue").asString(""))) {
      unary = op.equals("==") ? "IS_NAN" : op.equals("!=") ? "IS_NOT_NAN" : "";
    }
    if (unary != null) {
      if (unary.isEmpty()) {
        throw Refusal.bad(at + ": null and NaN compare only with == and !=.", null);
      }
      ObjectNode f = F.objectNode();
      f.putObject("unaryFilter").put("op", unary).putObject("field").put("fieldPath", field);
      return f;
    }
    ObjectNode f = F.objectNode();
    ObjectNode ff = f.putObject("fieldFilter");
    ff.putObject("field").put("fieldPath", field);
    ff.put("op", fsOp);
    ff.set("value", value);
    return f;
  }

  /**
   * A value {@code __name__} compares with: a reference, or a plain string — a document's path, or in a collection's
   * query the ID of one of its documents.
   */
  private static ObjectNode reference(JsonNode v, FirestoreSettings s, FirestorePath collection, String at)
      throws Refusal {
    if (v.isObject() && v.size() == 1 && v.has("referenceValue")) {
      return FirestoreValues.typed(v, s.documents(), at);
    }
    if (!v.isString()) {
      throw Refusal.bad(at + ": __name__ compares with a document's path, such as users/alice.", null);
    }
    String text = v.asString();
    if (!text.contains("/") && collection != null) {
      String problem = FirestorePath.problem(text);
      if (problem != null) {
        throw Refusal.bad(at + ": the document ID " + Names.shown(text) + " " + problem + ".", null);
      }
      return F.objectNode().put("referenceValue", collection.child(text).name(s.documents()));
    }
    return F.objectNode().put("referenceValue", FirestoreValues.reference(text, s.documents(), at));
  }

  /**
   * Where the page after {@code document} (as Firestore returned it) starts: its value of each field of the ordering,
   * its name for {@code __name__}; {@code null} when it lacks one.
   */
  ArrayNode position(JsonNode document) {
    ArrayNode values = F.arrayNode();
    for (Order o : order) {
      if (o.field().equals(FieldPaths.NAME)) {
        values.addObject().put("referenceValue", document.path("name").asString(""));
        continue;
      }
      JsonNode v = document.path("fields");
      for (int i = 0; i < o.names().size(); i++) {
        v = i == 0 ? v.get(o.names().get(i)) : v.path("mapValue").path("fields").get(o.names().get(i));
        if (v == null) {
          return null;
        }
      }
      values.add(v.deepCopy());
    }
    return values;
  }

  /** {@code users}, {@code every collection orders}: what a text names. */
  String target() {
    return collection != null ? collection.text() : "every collection " + group;
  }

  /** Where the query runs: the document above the collection, or the root for a collection group. */
  FirestorePath parentPath() {
    return collection != null ? collection.parent() : FirestorePath.ROOT;
  }

  /**
   * Whether the document {@code name} Firestore returned belongs to what was queried: a document of the collection,
   * or of a collection with the group's ID (defence in depth: Firestore answers only those).
   */
  boolean contains(String name, FirestoreSettings s) {
    String prefix = s.documents() + "/";
    if (!name.startsWith(prefix)) {
      return false;
    }
    String[] ids = name.substring(prefix.length()).split("/", -1);
    if (ids.length < 2 || ids.length % 2 != 0) {
      return false;
    }
    if (collection == null) {
      return ids[ids.length - 2].equals(group);
    }
    List<String> c = collection.segments();
    if (ids.length != c.size() + 1) {
      return false;
    }
    for (int i = 0; i < c.size(); i++) {
      if (!ids[i].equals(c.get(i))) {
        return false;
      }
    }
    return true;
  }
}
