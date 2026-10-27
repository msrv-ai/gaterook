package ai.msrv.gaterook.connectors.mongodb;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Whether a call stays a read (report 05 §3.3): MongoDB has no read-only session, and a user with {@code readWrite}
 * really writes through {@code $out} — even from {@code .first()} — so the gateway refuses before the network what
 * could write or run code, on the very documents the driver will send:
 * <ol>
 *   <li>a key {@code $out} or {@code $merge} at any depth of any argument (inside {@code $facet}, a {@code $lookup}
 *       or {@code $unionWith} pipeline, a {@code $literal}…) → {@code write-stage};</li>
 *   <li>a key {@code $where}, {@code $function} or {@code $accumulator} anywhere → {@code server-js} (server-side
 *       JavaScript is on by default in 7.0, 8.0 and 9.0);</li>
 *   <li>the collection, and every collection a stage reads ({@code $lookup}/{@code $graphLookup} {@code from},
 *       {@code $unionWith}), must be allowed by {@link Namespaces}; a {@code from} naming another database →
 *       {@code namespace-not-allowed};</li>
 *   <li>every stage of the pipeline and of every sub-pipeline is one object with one key from {@link #ALLOWED} (36
 *       of the 49 stages of MongoDB 9.0) → else {@code bad-stage} / {@code stage-not-allowed}. An unknown stage is
 *       refused: the list is fail-closed and follows the releases.</li>
 * </ol>
 * Pure and without I/O: {@code classify} runs it (≤ 1 ms), and {@code execute} runs it again before the network.
 */
final class PipelineValidator {

  /** Stages that only read (MongoDB 9.0 reference, report 05 §3.3). */
  static final Set<String> ALLOWED = Set.of("$addFields", "$set", "$bucket", "$bucketAuto", "$count", "$densify",
      "$documents", "$facet", "$fill", "$geoNear", "$graphLookup", "$group", "$limit", "$lookup", "$match",
      "$project", "$unset", "$redact", "$replaceRoot", "$replaceWith", "$sample", "$setWindowFields", "$skip",
      "$sort", "$sortByCount", "$unionWith", "$unwind", "$rankFusion", "$score", "$scoreFusion", "$search",
      "$searchMeta", "$vectorSearch", "$collStats", "$indexStats", "$listSearchIndexes");

  /** The 13 the gateway refuses by name, with why, so the agent learns more than "not allowed". */
  static final Map<String, String> REFUSED = Map.ofEntries(
      Map.entry("$out", "it writes to a collection"),
      Map.entry("$merge", "it writes to a collection"),
      Map.entry("$changeStream", "a change stream holds a cursor open"),
      Map.entry("$changeStreamSplitLargeEvent", "it belongs to change streams"),
      Map.entry("$currentOp", "it shows other clients' operations"),
      Map.entry("$listLocalSessions", "it lists other clients' sessions"),
      Map.entry("$listSessions", "it lists other clients' sessions"),
      Map.entry("$listSampledQueries", "it shows other clients' queries"),
      Map.entry("$planCacheStats", "it shows other clients' query shapes"),
      Map.entry("$querySettings", "it shows the cluster's query settings"),
      Map.entry("$queryStats", "it shows other clients' queries"),
      Map.entry("$shardedDataDistribution", "it shows cluster metadata"),
      Map.entry("$listClusterCatalog", "it lists the cluster's catalog"));

  static final Set<String> WRITE_KEYS = Set.of("$out", "$merge");
  static final Set<String> SERVER_JS_KEYS = Set.of("$where", "$function", "$accumulator");

  /** Why a call is refused: the rule's last segment ({@code <env>.mongodb.<rule>}), a text for the agent, a hint. */
  record Verdict(String rule, String reason, String hint) {}

  private final Namespaces namespaces;

  PipelineValidator(Namespaces namespaces) {
    this.namespaces = namespaces;
  }

  Optional<Verdict> check(String tool, MongoArgs args) {
    Optional<Verdict> v = switch (args) {
      case MongoArgs.Find f -> scan(f.filter(), tool)
          .or(() -> f.projection() == null ? Optional.empty() : scan(f.projection(), tool))
          .or(() -> f.sort() == null ? Optional.empty() : scan(f.sort(), tool));
      case MongoArgs.Aggregate a -> scan(a.pipeline(), tool);
    };
    if (v.isPresent()) {
      return v;
    }
    if (!namespaces.allowed(args.database(), args.collection())) {
      return Optional.of(namespace(args.database(), args.collection()));
    }
    if (args instanceof MongoArgs.Aggregate a) {
      return pipeline(a.pipeline(), args.database(), "pipeline", 0);
    }
    return Optional.empty();
  }

  /** Every key of every document at any depth: writes and server-side JavaScript. */
  private static Optional<Verdict> scan(BsonValue value, String tool) {
    if (value instanceof BsonDocument d) {
      for (Map.Entry<String, BsonValue> e : d.entrySet()) {
        String key = e.getKey();
        if (WRITE_KEYS.contains(key)) {
          return Optional.of(new Verdict("write-stage", key + " writes to a collection. " + tool + " is read-only.",
              "Return the documents instead; a person can write them where they belong."));
        }
        if (SERVER_JS_KEYS.contains(key)) {
          return Optional.of(new Verdict("server-js", "Server-side JavaScript (" + key + ") isn't allowed. Use $expr "
              + "with aggregation operators instead.", null));
        }
        Optional<Verdict> inner = scan(e.getValue(), tool);
        if (inner.isPresent()) {
          return inner;
        }
      }
    } else if (value instanceof BsonArray a) {
      for (BsonValue v : a) {
        Optional<Verdict> inner = scan(v, tool);
        if (inner.isPresent()) {
          return inner;
        }
      }
    }
    return Optional.empty();
  }

  private Optional<Verdict> pipeline(BsonArray stages, String database, String where, int depth) {
    if (depth > 8) {
      return Optional.of(new Verdict("bad-stage", "Sub-pipelines are nested deeper than 8 levels.", null));
    }
    for (int i = 0; i < stages.size(); i++) {
      BsonValue s = stages.get(i);
      String label = where + " stage " + (i + 1);
      if (!(s instanceof BsonDocument stage) || stage.size() != 1) {
        return Optional.of(new Verdict("bad-stage", "The " + label + " must be an object with one key, like "
            + "{\"$match\": {…}}.", null));
      }
      String name = stage.getFirstKey();
      BsonValue body = stage.get(name);
      if (!ALLOWED.contains(name)) {
        String why = REFUSED.get(name);
        return Optional.of(new Verdict("stage-not-allowed", "Stage " + name + " isn't allowed by the gateway"
            + (why == null ? "." : ": " + why + "."), why == null ? "Stages mongo_aggregate runs: " + String.join(
                " ", new java.util.TreeSet<>(ALLOWED)) + "." : null));
      }
      Optional<Verdict> inner = stageBody(name, body, database, label, depth);
      if (inner.isPresent()) {
        return inner;
      }
    }
    return Optional.empty();
  }

  /** The collections a stage reads and the pipelines inside it. */
  private Optional<Verdict> stageBody(String name, BsonValue body, String database, String label, int depth) {
    switch (name) {
      case "$lookup", "$graphLookup" -> {
        if (body instanceof BsonDocument d) {
          Optional<Verdict> from = from(d.get("from"), database, name);
          if (from.isPresent()) {
            return from;
          }
          if (d.get("pipeline") instanceof BsonArray sub) {
            return pipeline(sub, database, name + " pipeline of the " + label, depth + 1);
          }
        }
      }
      case "$unionWith" -> {
        if (body != null && body.isString()) {
          return from(body, database, name);
        }
        if (body instanceof BsonDocument d) {
          Optional<Verdict> from = from(d.get("coll"), database, name);
          if (from.isPresent()) {
            return from;
          }
          if (d.get("pipeline") instanceof BsonArray sub) {
            return pipeline(sub, database, "$unionWith pipeline of the " + label, depth + 1);
          }
        }
      }
      case "$facet" -> {
        if (body instanceof BsonDocument d) {
          for (Map.Entry<String, BsonValue> e : d.entrySet()) {
            if (!(e.getValue() instanceof BsonArray sub)) {
              return Optional.of(new Verdict("bad-stage", "$facet " + e.getKey() + " in the " + label + " must be "
                  + "a pipeline (an array of stages).", null));
            }
            Optional<Verdict> inner = pipeline(sub, database, "$facet " + e.getKey() + " of the " + label,
                depth + 1);
            if (inner.isPresent()) {
              return inner;
            }
          }
        }
      }
      case "$rankFusion", "$scoreFusion" -> {
        // {input: {pipelines: {name: [stages], …}}}: each is a pipeline of its own.
        if (body instanceof BsonDocument d && d.get("input") instanceof BsonDocument input
            && input.get("pipelines") instanceof BsonDocument pipelines) {
          for (Map.Entry<String, BsonValue> e : pipelines.entrySet()) {
            if (e.getValue() instanceof BsonArray sub) {
              Optional<Verdict> inner = pipeline(sub, database, name + " " + e.getKey() + " of the " + label,
                  depth + 1);
              if (inner.isPresent()) {
                return inner;
              }
            }
          }
        }
      }
      default -> {
        // The other allowed stages read only the collection of the call.
      }
    }
    return Optional.empty();
  }

  private Optional<Verdict> from(BsonValue from, String database, String stage) {
    if (from == null) {
      return Optional.empty();
    }
    if (from.isDocument()) {
      return Optional.of(new Verdict("namespace-not-allowed", stage + " from another database isn't allowed by the "
          + "gateway: read collections of " + database + " only.", null));
    }
    if (!from.isString()) {
      return Optional.of(new Verdict("bad-stage", stage + " names its collection with a string.", null));
    }
    String coll = from.asString().getValue();
    if (!namespaces.allowed(database, coll)) {
      return Optional.of(namespace(database, coll));
    }
    return Optional.empty();
  }

  private Verdict namespace(String database, String collection) {
    return new Verdict("namespace-not-allowed", namespaces.refusal(database, collection), null);
  }
}
