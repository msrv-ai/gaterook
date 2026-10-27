package ai.msrv.gaterook.connectors.mongodb;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.EstimatedDocumentCountOptions;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonType;
import org.bson.BsonValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The connector's part of {@code describe_connection} (report 05 §3.7, ≤ 2 s): the server, then what the agent may
 * read — the databases the user is authorized for ({@code listDatabases {nameOnly, authorizedDatabases}} works without
 * the {@code listDatabases} privilege) and their collections, views and time series ({@code listCollections
 * {nameOnly, authorizedCollections}}), with estimated counts when there are few. Only what {@link Namespaces} allows
 * is listed: no {@code admin}, {@code local}, {@code config} or {@code system.*} without the allowlist naming them.
 * With an object ({@code orders.orders}, or {@code orders} in the default database): its indexes and the types of
 * its top-level fields in a {@code $sample} of 20. What doesn't fit the deadline is left out ({@code truncated}).
 */
final class MongoDescribe {

  static final int MAX_DATABASES = 50;
  static final int MAX_COLLECTIONS = 200;
  static final int COUNT_UP_TO = 50;
  static final int SAMPLE = 20;
  /** Stop starting new commands when less than this is left of the deadline. */
  static final long MARGIN_MS = 150;

  private MongoDescribe() {}

  static JsonNode describe(MongoClient client, MongoSettings s, Namespaces ns, ExecContext ctx, String object)
      throws ObjectRefused {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    MongoDatabase admin = client.getDatabase("admin");
    BsonDocument build = admin.runCommand(new BsonDocument("buildInfo", new BsonInt32(1)), BsonDocument.class);
    out.putObject("server").put("product", "MongoDB")
        .put("version", build.getString("version", new BsonString("?")).getValue());
    out.put("defaultDatabase", s.defaultDatabase());
    out.put("readPreference", s.readPreference());
    ArrayNode allow = out.putArray("namespaceAllowlist");
    s.namespaceAllowlist().forEach(allow::add);
    out.put("readsOnly", "mongo_find and mongo_aggregate read; $out, $merge and server-side JavaScript are refused.");
    boolean truncated = false;
    if (object != null && !object.isBlank()) {
      // database.collection when the first part is the default or a database the user sees; otherwise a
      // collection of the default database (collection names may contain dots).
      String db = s.defaultDatabase();
      String coll = object;
      int dot = object.indexOf('.');
      if (dot > 0) {
        String first = object.substring(0, dot);
        if (db == null || first.equals(db) || looksLikeDatabase(client, first)) {
          db = first;
          coll = object.substring(dot + 1);
        }
      }
      if (db == null) {
        throw new ObjectRefused("Name the database: database.collection (this connection has no single default).");
      }
      if (!ns.allowed(db, coll)) {
        throw new ObjectRefused(ns.refusal(db, coll));
      }
      out.set("object", collection(client.getDatabase(db), db, coll, ctx));
      return out;
    }
    ArrayNode dbs = out.putArray("databases");
    List<String> names = databases(admin);
    int listed = 0;
    for (String name : names) {
      if (!ns.databaseVisible(name)) {
        continue;
      }
      if (listed++ >= MAX_DATABASES || left(ctx) < MARGIN_MS) {
        truncated = true;
        break;
      }
      ObjectNode d = dbs.addObject();
      d.put("name", name);
      Listing listing = collections(client.getDatabase(name), name, ns);
      truncated |= listing.truncated();
      ArrayNode colls = d.putArray("collections");
      boolean count = listing.items().size() <= COUNT_UP_TO;
      for (String[] c : listing.items()) {
        ObjectNode n = colls.addObject();
        n.put("name", c[0]);
        n.put("type", c[1]);
        if (count && "collection".equals(c[1]) && left(ctx) > MARGIN_MS) {
          try {
            n.put("estimatedDocuments", client.getDatabase(name).getCollection(c[0])
                .estimatedDocumentCount(new EstimatedDocumentCountOptions().maxTime(Math.max(1, Math.min(500,
                    left(ctx) - MARGIN_MS)), TimeUnit.MILLISECONDS)));
          } catch (RuntimeException e) {
            // A count the user may not run, or no time left: the name is enough.
          }
        }
      }
    }
    out.put("truncated", truncated);
    return out;
  }

  /** {@code orders.orders}: the indexes and the field types of a sample. */
  private static ObjectNode collection(MongoDatabase database, String db, String coll, ExecContext ctx) {
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    o.put("database", db);
    o.put("collection", coll);
    ArrayNode indexes = o.putArray("indexes");
    try (MongoCursor<BsonDocument> it = database.getCollection(coll, BsonDocument.class)
        .listIndexes(BsonDocument.class).batchSize(100).cursor()) {
      while (it.hasNext() && indexes.size() < 100) {
        BsonDocument index = it.next();
        ObjectNode i = indexes.addObject();
        i.put("name", index.getString("name", new BsonString("?")).getValue());
        i.set("key", Ejson.tree(index.getDocument("key", new BsonDocument())));
        if (index.getBoolean("unique", BsonBoolean.FALSE).getValue()) {
          i.put("unique", true);
        }
      }
    }
    if (left(ctx) > MARGIN_MS) {
      Map<String, TreeSet<String>> types = new TreeMap<>();
      int sampled = 0;
      try (MongoCursor<BsonDocument> it = database.getCollection(coll, BsonDocument.class)
          .aggregate(List.of(new BsonDocument("$sample", new BsonDocument("size", new BsonInt32(SAMPLE)))))
          .allowDiskUse(false).maxTime(Math.max(1, left(ctx) - MARGIN_MS), TimeUnit.MILLISECONDS)
          .comment("mcp-gateway " + ctx.callId()).cursor()) {
        while (it.hasNext()) {
          sampled++;
          for (Map.Entry<String, BsonValue> f : it.next().entrySet()) {
            types.computeIfAbsent(f.getKey(), k -> new TreeSet<>()).add(typeName(f.getValue().getBsonType()));
          }
        }
      }
      ObjectNode fields = o.putObject("fields");
      types.forEach((field, set) -> {
        ArrayNode a = fields.putArray(field);
        set.forEach(a::add);
      });
      o.put("sampled", sampled);
    } else {
      o.put("truncated", true);
    }
    return o;
  }

  private record Listing(List<String[]> items, boolean truncated) {}

  private static Listing collections(MongoDatabase database, String db, Namespaces ns) {
    BsonDocument r;
    try {
      r = database.runCommand(new BsonDocument("listCollections", new BsonInt32(1))
          .append("nameOnly", BsonBoolean.TRUE).append("authorizedCollections", BsonBoolean.TRUE)
          .append("cursor", new BsonDocument("batchSize", new BsonInt32(MAX_COLLECTIONS))), BsonDocument.class);
    } catch (MongoCommandException e) {
      return new Listing(List.of(), false);
    }
    BsonDocument cursor = r.getDocument("cursor", new BsonDocument());
    List<String[]> out = new ArrayList<>();
    for (BsonValue v : cursor.getArray("firstBatch", new BsonArray())) {
      if (!v.isDocument()) {
        continue;
      }
      String name = v.asDocument().getString("name", new BsonString("")).getValue();
      String type = v.asDocument().getString("type", new BsonString("collection")).getValue();
      if (!name.isEmpty() && ns.allowed(db, name)) {
        out.add(new String[] {name, type});
      }
    }
    out.sort((a, b) -> a[0].compareTo(b[0]));
    long id = cursor.getNumber("id", new BsonInt64(0)).longValue();
    if (id != 0) {
      try {
        database.runCommand(new BsonDocument("killCursors", new BsonString("$cmd.listCollections"))
            .append("cursors", new BsonArray(List.of(new BsonInt64(id)))), BsonDocument.class);
      } catch (RuntimeException ignored) {
        // The server drops an idle cursor after ten minutes anyway.
      }
    }
    return new Listing(out, id != 0);
  }

  private static List<String> databases(MongoDatabase admin) {
    BsonDocument r = admin.runCommand(new BsonDocument("listDatabases", new BsonInt32(1))
        .append("nameOnly", BsonBoolean.TRUE).append("authorizedDatabases", BsonBoolean.TRUE), BsonDocument.class);
    List<String> out = new ArrayList<>();
    for (BsonValue d : r.getArray("databases", new BsonArray())) {
      if (d.isDocument() && d.asDocument().containsKey("name")) {
        out.add(d.asDocument().getString("name").getValue());
      }
    }
    out.sort(null);
    return out;
  }

  private static boolean looksLikeDatabase(MongoClient client, String name) {
    try {
      return databases(client.getDatabase("admin")).contains(name);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static long left(ExecContext ctx) {
    return ctx.remaining().toMillis();
  }

  static String typeName(BsonType t) {
    return switch (t) {
      case DOUBLE -> "double";
      case STRING -> "string";
      case DOCUMENT -> "object";
      case ARRAY -> "array";
      case BINARY -> "binData";
      case OBJECT_ID -> "objectId";
      case BOOLEAN -> "bool";
      case DATE_TIME -> "date";
      case NULL -> "null";
      case REGULAR_EXPRESSION -> "regex";
      case INT32 -> "int";
      case TIMESTAMP -> "timestamp";
      case INT64 -> "long";
      case DECIMAL128 -> "decimal";
      default -> t.name().toLowerCase(java.util.Locale.ROOT);
    };
  }

  /** An object the agent may not describe. */
  static final class ObjectRefused extends Exception {
    private static final long serialVersionUID = 1L;

    ObjectRefused(String message) {
      super(message, null, false, false);
    }
  }
}
