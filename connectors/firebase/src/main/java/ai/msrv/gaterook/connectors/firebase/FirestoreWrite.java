package ai.msrv.gaterook.connectors.firebase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One write of {@code firestore_write} as the body of {@code documents:commit}: exactly one write, on one document of
 * a collection on the connection's list, with a precondition that makes its outcome exact.
 *
 * <ul>
 *   <li>create: {@code update} of the whole document with {@code currentDocument.exists = false}: refused if it
 *       exists;</li>
 *   <li>update: {@code update} with an {@code updateMask} of the given fields and {@code currentDocument.exists =
 *       true}: the other fields stay, refused if the document doesn't exist;</li>
 *   <li>delete: {@code delete} with {@code currentDocument.exists = true}: refused if there is nothing to delete.
 *       Firestore keeps the document's subcollections.</li>
 * </ul>
 *
 * <p>The body is the approval's payload and what runs: {@link #approved} reads a payload back and accepts exactly
 * these three shapes, nothing else.
 */
record FirestoreWrite(String operation, FirestorePath document, ObjectNode fields, ObjectNode commit) {

  static final Set<String> OPERATIONS = Set.of("create", "update", "delete");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** The write the arguments of {@code firestore_write} ask for. */
  static FirestoreWrite of(JsonNode args, FirestoreSettings s, long maxBytes) throws Refusal {
    String operation = args.path("operation").isString() ? args.get("operation").asString() : null;
    if (operation == null || !OPERATIONS.contains(operation)) {
      throw Refusal.bad("operation must be create, update or delete.", null);
    }
    FirestorePath document = document(args.path("document"), s);
    JsonNode given = args.path("fields");
    boolean hasFields = !given.isMissingNode() && !given.isNull();
    ObjectNode fields;
    switch (operation) {
      case "delete" -> {
        if (hasFields) {
          throw Refusal.bad("delete takes no fields: it removes the whole document.", null);
        }
        fields = F.objectNode();
      }
      case "update" -> {
        fields = FirestoreValues.fields(given, s.documents(), "fields");
        if (fields.isEmpty()) {
          throw Refusal.bad("update needs the fields to set.", null);
        }
      }
      default -> fields = FirestoreValues.fields(given, s.documents(), "fields");
    }
    FirestoreWrite w = new FirestoreWrite(operation, document, fields, commit(operation, document, fields, s));
    long bytes = Names.utf8Length(w.text());
    if (maxBytes > 0 && bytes > maxBytes) {
      throw new Refusal(Refusal.TOO_LARGE, "The write is " + GoogleHttp.size(bytes) + ", larger than the "
          + GoogleHttp.size(maxBytes) + " a request may be on this endpoint.", "Write fewer or smaller fields.");
    }
    return w;
  }

  /**
   * The approved payload read back: one write of the three shapes on a document of this database whose collection
   * the connection allows, every value one of Firestore's typed values, checked again. What runs is this write made
   * again from its parts, so nothing the shapes don't name (a transform, another write) can ride along.
   */
  static FirestoreWrite approved(JsonNode commit, FirestoreSettings s) throws Refusal {
    if (commit == null || !commit.isObject() || commit.size() != 1 || !commit.path("writes").isArray()
        || commit.get("writes").size() != 1 || !commit.get("writes").get(0).isObject()) {
      throw tampered();
    }
    JsonNode w = commit.get("writes").get(0);
    JsonNode exists = w.path("currentDocument");
    if (!exists.isObject() || exists.size() != 1 || !exists.path("exists").isBoolean()) {
      throw tampered();
    }
    boolean mustExist = exists.get("exists").booleanValue();
    String operation;
    String name;
    JsonNode given;
    if (w.has("delete")) {
      if (w.size() != 2 || !w.get("delete").isString() || !mustExist) {
        throw tampered();
      }
      operation = "delete";
      name = w.get("delete").asString();
      given = F.objectNode();
    } else {
      JsonNode update = w.path("update");
      boolean masked = w.has("updateMask");
      if (!update.isObject() || update.size() != 2 || !update.path("name").isString()
          || !update.path("fields").isObject() || w.size() != (masked ? 3 : 2) || masked != mustExist) {
        throw tampered();
      }
      operation = masked ? "update" : "create";
      name = update.get("name").asString();
      given = update.get("fields");
    }
    if (!name.startsWith(s.documents() + "/")) {
      throw new Refusal(Refusal.NOT_ALLOWED_COLLECTION, "The approved write is for a document of another database.",
          null);
    }
    FirestorePath document = document(F.stringNode(name.substring(s.documents().length() + 1)), s);
    ObjectNode fields = FirestoreValues.typedFields(given, s.documents(), "fields");
    if (operation.equals("update")) {
      JsonNode mask = w.get("updateMask");
      Set<String> expected = new HashSet<>();
      fields.propertyNames().forEach(f -> expected.add(FieldPaths.quote(f)));
      Set<String> masked = new HashSet<>();
      mask.path("fieldPaths").forEach(p -> masked.add(p.asString("")));
      if (!mask.isObject() || mask.size() != 1 || fields.isEmpty() || mask.path("fieldPaths").size() != fields.size()
          || !masked.equals(expected)) {
        throw tampered();
      }
    }
    return new FirestoreWrite(operation, document, fields, commit(operation, document, fields, s));
  }

  private static Refusal tampered() {
    return Refusal.bad("The approved action isn't one Firestore write of firestore_write. Not executed.", null);
  }

  private static FirestorePath document(JsonNode v, FirestoreSettings s) throws Refusal {
    if (!v.isString()) {
      throw Refusal.bad("document must be a document's path, such as users/alice.", null);
    }
    FirestorePath p = FirestorePath.parse(v.asString());
    if (!p.document()) {
      throw Refusal.bad("document " + Names.shown(v.asString()) + " isn't a document: a document's path has an even "
          + "number of IDs, such as users/alice or users/alice/orders/o-1.", null);
    }
    if (!s.allowsCollection(p.parent())) {
      throw new Refusal(Refusal.NOT_ALLOWED_COLLECTION, Names.shown(p.parent().text()) + " isn't one of the "
          + "collections agents may use on this connection.", "Call firestore_list_collections to see the "
          + "collections you can use.");
    }
    return p;
  }

  private static ObjectNode commit(String operation, FirestorePath document, ObjectNode fields,
      FirestoreSettings s) {
    ObjectNode body = F.objectNode();
    ObjectNode w = body.putArray("writes").addObject();
    String name = document.name(s.documents());
    if (operation.equals("delete")) {
      w.put("delete", name);
    } else {
      ObjectNode update = w.putObject("update");
      update.put("name", name);
      update.set("fields", fields.deepCopy());
      if (operation.equals("update")) {
        ArrayNode mask = w.putObject("updateMask").putArray("fieldPaths");
        fields.propertyNames().forEach(f -> mask.add(FieldPaths.quote(f)));
      }
    }
    w.putObject("currentDocument").put("exists", !operation.equals("create"));
    return body;
  }

  /** The names of the fields written, for the approver. */
  List<String> fieldNames() {
    return new ArrayList<>(fields.propertyNames());
  }

  /** The body of the commit: the approval's text and the bytes sent alike. */
  String text() {
    return GoogleHttp.PRETTY.writeValueAsString(commit);
  }
}
