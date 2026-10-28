package ai.msrv.gaterook.connectors.mongodb;

import ai.msrv.gaterook.connectors.mongodb.Ejson.BadArgument;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The arguments of one call, parsed once into the documents the validator checks and the driver sends (report 05
 * §3.1, §3.2). The same text always parses to the same documents, so {@code classify} and {@code execute} — separate
 * calls of the core — see one meaning.
 */
sealed interface MongoArgs {

  int MAX_PIPELINE = 50;
  int MAX_LIMIT = 1_000;
  int MAX_SKIP = 100_000;

  String database();

  String collection();

  int limit();

  /** The arguments in canonical form: what an approved call runs again ({@code ActionPreview.payload}). */
  ObjectNode canonical();

  /** {@code mongo_find}. */
  record Find(String database, String collection, BsonDocument filter, BsonDocument projection, BsonDocument sort,
      int limit, int skip) implements MongoArgs {

    @Override
    public ObjectNode canonical() {
      ObjectNode n = JsonNodeFactory.instance.objectNode();
      n.put("database", database);
      n.put("collection", collection);
      n.put("filter", Ejson.canonical(filter));
      if (projection != null) {
        n.put("projection", Ejson.canonical(projection));
      }
      if (sort != null) {
        n.put("sort", Ejson.canonical(sort));
      }
      n.put("limit", limit);
      n.put("skip", skip);
      return n;
    }
  }

  /** {@code mongo_aggregate}. */
  record Aggregate(String database, String collection, BsonArray pipeline, int limit) implements MongoArgs {

    @Override
    public ObjectNode canonical() {
      ObjectNode n = JsonNodeFactory.instance.objectNode();
      n.put("database", database);
      n.put("collection", collection);
      n.set("pipeline", Ejson.JSON.readTree(Ejson.canonical(pipeline)));
      n.put("limit", limit);
      return n;
    }
  }

  /** A single-document or multi-document update; {@code many} is bound to the tool in approved payloads. */
  record Update(String database, String collection, BsonDocument filter, BsonValue update, boolean many,
      boolean upsert, BsonArray arrayFilters) implements MongoArgs {

    /** Read pagination does not apply to updates. */
    @Override
    public int limit() {
      return 1;
    }

    @Override
    public ObjectNode canonical() {
      ObjectNode n = JsonNodeFactory.instance.objectNode();
      n.put("database", database);
      n.put("collection", collection);
      n.put("filter", Ejson.canonical(filter));
      if (update.isArray()) {
        n.set("update", Ejson.JSON.readTree(Ejson.canonical(update)));
      } else {
        n.put("update", Ejson.canonical(update));
      }
      n.put("many", many);
      n.put("upsert", upsert);
      if (arrayFilters != null) {
        n.set("arrayFilters", Ejson.JSON.readTree(Ejson.canonical(arrayFilters)));
      }
      return n;
    }
  }

  /**
   * Parses the arguments of {@code tool}. {@code defaultDatabase} is the connection string's; without one the
   * argument is required.
   */
  static MongoArgs parse(String tool, JsonNode args, String defaultDatabase) {
    if (args == null || !args.isObject()) {
      throw new BadArgument("arguments", "Arguments must be an object.");
    }
    String database = text(args, "database");
    if (database == null || database.isBlank()) {
      database = defaultDatabase;
    }
    if (database == null || database.isBlank()) {
      throw new BadArgument("database", "Name the database: this connection has no single default database.");
    }
    checkDatabase(database);
    String collection = text(args, "collection");
    if (collection == null || collection.isEmpty()) {
      throw new BadArgument("collection", "Name the collection.");
    }
    checkCollection(collection);
    return switch (tool) {
      case MongoTools.FIND -> {
        int limit = integer(args, "limit", 50, 1, MAX_LIMIT);
        BsonDocument filter = Ejson.document(args.get("filter"), "filter");
        BsonDocument projection = args.hasNonNull("projection") ? Ejson.document(args.get("projection"),
            "projection") : null;
        BsonDocument sort = args.hasNonNull("sort") ? Ejson.document(args.get("sort"), "sort") : null;
        int skip = integer(args, "skip", 0, 0, MAX_SKIP);
        yield new Find(database, collection, filter, projection, sort, limit, skip);
      }
      case MongoTools.AGGREGATE -> {
        int limit = integer(args, "limit", 50, 1, MAX_LIMIT);
        BsonArray pipeline = Ejson.array(args.get("pipeline"), "pipeline");
        if (pipeline.isEmpty() || pipeline.size() > MAX_PIPELINE) {
          throw new BadArgument("pipeline", "A pipeline has 1 to " + MAX_PIPELINE + " stages.");
        }
        yield new Aggregate(database, collection, pipeline, limit);
      }
      case MongoTools.UPDATE, MongoTools.UPDATE_MANY -> {
        for (String field : List.of("limit", "skip", "projection", "sort")) {
          if (args.has(field)) {
            throw new BadArgument(field, field + " isn't supported by updates. "
                + "mongo_update changes at most one document; mongo_update_many changes every matching document.");
          }
        }
        if (!args.hasNonNull("filter")) {
          throw new BadArgument("filter", "Provide a filter explicitly. Use {} to match every document.");
        }
        if (!args.hasNonNull("update")) {
          throw new BadArgument("update", "Provide an update document or an update pipeline.");
        }
        BsonDocument filter = Ejson.document(args.get("filter"), "filter");
        BsonValue update;
        if (args.get("update").isArray()) {
          BsonArray stages = Ejson.array(args.get("update"), "update");
          if (stages.isEmpty() || stages.size() > MAX_PIPELINE) {
            throw new BadArgument("update", "An update pipeline has 1 to " + MAX_PIPELINE + " stages.");
          }
          update = stages;
        } else {
          BsonDocument document = Ejson.document(args.get("update"), "update");
          if (document.isEmpty()) {
            throw new BadArgument("update", "An update document must contain at least one update operator.");
          }
          update = document;
        }
        boolean many = MongoTools.UPDATE_MANY.equals(tool);
        if (args.has("many") && bool(args, "many", many) != many) {
          throw new BadArgument("many", "The approved update operation doesn't match " + tool + ".");
        }
        boolean upsert = bool(args, "upsert", false);
        BsonArray arrayFilters = null;
        if (args.has("arrayFilters")) {
          arrayFilters = Ejson.array(args.get("arrayFilters"), "arrayFilters");
          if (update.isArray()) {
            throw new BadArgument("arrayFilters", "arrayFilters can't be used with an update pipeline.");
          }
          for (BsonValue filterValue : arrayFilters) {
            if (!filterValue.isDocument()) {
              throw new BadArgument("arrayFilters", "Each array filter must be an object.");
            }
          }
        }
        yield new Update(database, collection, filter, update, many, upsert, arrayFilters);
      }
      default -> throw new BadArgument("tool", "Unknown tool " + tool + ".");
    };
  }

  private static boolean bool(JsonNode args, String field, boolean dflt) {
    JsonNode n = args.get(field);
    if (n == null) {
      return dflt;
    }
    if (!n.isBoolean()) {
      throw new BadArgument(field, field + " must be a boolean.");
    }
    return n.asBoolean();
  }

  private static String text(JsonNode args, String field) {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isString()) {
      throw new BadArgument(field, field + " must be a string.");
    }
    return n.asString();
  }

  private static int integer(JsonNode args, String field, int dflt, int min, int max) {
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return dflt;
    }
    if (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < min || n.asInt() > max) {
      throw new BadArgument(field, field + " must be an integer from " + min + " to " + max + ".");
    }
    return n.asInt();
  }

  /** The server's rules for database names, before anything is sent. */
  static void checkDatabase(String name) {
    if (name.length() > 64 || name.chars().anyMatch(c -> "/\\. \"$\0".indexOf(c) >= 0)) {
      throw new BadArgument("database", "Database names have at most 64 characters and none of / \\ . space \" $.");
    }
  }

  static void checkCollection(String name) {
    if (name.length() > 255 || name.indexOf('$') >= 0 || name.indexOf('\0') >= 0 || name.startsWith(".")
        || name.endsWith(".")) {
      throw new BadArgument("collection", "Collection names have at most 255 characters, no $, and don't start or "
          + "end with a dot.");
    }
  }
}
