package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The arguments of one call, checked without I/O — the same checks in {@code classify} (a refusal is a DENY with its
 * rule) and again in {@code execute} (report 05 §3.6). They come from the agent's arguments or, for a call a person
 * approved, from the payload stored with the request, which has the same shape ({@link #payload()}).
 */
sealed interface RedisRequest permits RedisRequest.Get, RedisRequest.Scan {

  String GET_TOOL = "redis_get";
  String SCAN_TOOL = "redis_scan";
  Set<String> TYPES = Set.of("string", "hash", "list", "set", "zset", "stream");

  /** The canonical arguments: what a person approves and what runs after the approval. */
  ObjectNode payload();

  int database();

  /**
   * {@code redis_get}: one key.
   *
   * @param key    the key's bytes ({@code encoding=base64} for a key that isn't UTF-8, as {@code redis_scan} lists it)
   * @param limit  elements asked for (the call's row limit cuts it further)
   */
  record Get(byte[] key, String keyText, boolean base64, int limit, String cursor, int database) implements RedisRequest {
    @Override
    public ObjectNode payload() {
      ObjectNode p = JsonNodeFactory.instance.objectNode().put("tool", GET_TOOL).put("key", keyText)
          .put("database", database);
      if (base64) {
        p.put("encoding", "base64");
      }
      p.put("limit", limit);
      if (cursor != null) {
        p.put("cursor", cursor);
      }
      return p;
    }
  }

  /** {@code redis_scan}: keys matching {@code pattern}, of {@code type} if given. */
  record Scan(String pattern, String type, int count, String cursor, boolean details, int database) implements RedisRequest {
    @Override
    public ObjectNode payload() {
      ObjectNode p = JsonNodeFactory.instance.objectNode().put("tool", SCAN_TOOL).put("pattern", pattern)
          .put("database", database);
      if (type != null) {
        p.put("type", type);
      }
      p.put("count", count).put("details", details);
      if (cursor != null) {
        p.put("cursor", cursor);
      }
      return p;
    }
  }

  /** A call the connector won't run: {@code rule} is the last part of the policy rule id. */
  final class Refusal extends Exception {
    private static final long serialVersionUID = 1L;
    final String rule;
    final String hint;

    Refusal(String rule, String message, String hint) {
      super(message);
      this.rule = rule;
      this.hint = hint;
    }
  }

  /** The request of {@code tool} with {@code arguments} (the agent's, or an approved payload) on {@code cfg}. */
  static RedisRequest of(String tool, JsonNode arguments, ConnectionConfig cfg, RedisSettings s) throws Refusal {
    s = s.withDatabase(database(arguments, s));
    return switch (tool) {
      case GET_TOOL -> get(arguments, cfg, s);
      case SCAN_TOOL -> scan(arguments, cfg, s);
      default -> throw new Refusal("unknown-tool", "The Redis connector has no tool " + tool + ".", null);
    };
  }

  private static Get get(JsonNode a, ConnectionConfig cfg, RedisSettings s) throws Refusal {
    String keyText = a.path("key").isString() ? a.path("key").asString() : null;
    if (keyText == null || keyText.isEmpty() || keyText.length() > 1_024) {
      throw new Refusal("bad-argument", "key is 1 to 1,024 characters.", null);
    }
    String encoding = a.path("encoding").asString("utf8");
    boolean base64 = encoding.equals("base64");
    if (!base64 && !encoding.equals("utf8")) {
      throw new Refusal("bad-argument", "encoding is utf8 or base64.", null);
    }
    byte[] key;
    if (base64) {
      try {
        key = Base64.getDecoder().decode(keyText);
      } catch (IllegalArgumentException e) {
        throw new Refusal("bad-argument", "key isn't base64, but encoding says it is.",
            "Pass the key exactly as redis_scan listed it.");
      }
      if (key.length == 0) {
        throw new Refusal("bad-argument", "key is empty.", null);
      }
    } else {
      key = keyText.getBytes(StandardCharsets.UTF_8);
    }
    if (!s.keyAllowed(key)) {
      throw new Refusal("key-not-allowed", cfg.name() + " doesn't let agents read " + RedisValues.quote(key) + ": "
          + s.whyNotAllowed(key) + ".", "Read keys the connection allows; describe_connection shows its key lists.");
    }
    int limit = bounded(a, "limit", 100);
    String cursor = cursor(a);
    if (cursor != null) {
      java.util.Optional<String> why = Cursors.peekGet(cursor, s.cursorScope(cfg.id()), key);
      if (why.isPresent()) {
        throw new Refusal("bad-cursor", why.get(), null);
      }
    }
    return new Get(key, keyText, base64, limit, cursor, s.database());
  }

  private static Scan scan(JsonNode a, ConnectionConfig cfg, RedisSettings s) throws Refusal {
    String pattern = a.path("pattern").isString() ? a.path("pattern").asString() : "*";
    if (pattern.isEmpty() || pattern.length() > RedisSettings.MAX_PATTERN || RedisSettings.hasControl(pattern)) {
      throw new Refusal("bad-pattern", "pattern is a Redis glob of 1 to " + RedisSettings.MAX_PATTERN
          + " characters without control characters.", "For example cache:*, user:?:profile or order:[0-9]*.");
    }
    String type = a.path("type").isString() ? a.path("type").asString() : null;
    if (type != null && !TYPES.contains(type)) {
      throw new Refusal("bad-argument", "type is one of string, hash, list, set, zset, stream.", null);
    }
    int count = bounded(a, "count", 100);
    String cursor = cursor(a);
    if (cursor != null) {
      java.util.Optional<String> why = Cursors.peekScan(cursor, s.cursorScope(cfg.id()), pattern, type);
      if (why.isPresent()) {
        throw new Refusal("bad-cursor", why.get(), null);
      }
    }
    return new Scan(pattern, type, count, cursor, a.path("details").asBoolean(true), s.database());
  }

  private static int database(JsonNode arguments, RedisSettings s) throws Refusal {
    JsonNode db = arguments.path("database");
    if (db.isMissingNode() || db.isNull()) {
      if (s.databases().size() > 1) {
        throw new Refusal("database-required", "Choose a database for this call.",
            "Pass one of the connection's selected database numbers in database.");
      }
      return s.database();
    }
    if (!db.isIntegralNumber() || db.asLong() < 0 || db.asLong() > 1_023) {
      throw new Refusal("bad-argument", "database is a whole number from 0 to 1,023.", null);
    }
    int selected = db.asInt();
    if (s.databases().isEmpty() ? selected != s.database() : !s.databases().contains(selected)) {
      throw new Refusal("database-not-allowed", "Database " + selected + " isn't selected for this connection.",
          "Use a database selected in the connection settings.");
    }
    return selected;
  }

  private static String cursor(JsonNode a) throws Refusal {
    JsonNode c = a.path("cursor");
    if (c.isMissingNode() || c.isNull()) {
      return null;
    }
    if (!c.isString() || c.asString().length() > 512) {
      throw new Refusal("bad-cursor", "cursor is the nextCursor of the previous call, as it came.", null);
    }
    return c.asString().isEmpty() ? null : c.asString();
  }

  private static int bounded(JsonNode a, String field, int dflt) throws Refusal {
    JsonNode n = a.path(field);
    if (n.isMissingNode() || n.isNull()) {
      return dflt;
    }
    if (!n.isIntegralNumber() || n.asLong() < 1 || n.asLong() > 1_000) {
      throw new Refusal("bad-argument", field + " is a whole number from 1 to 1,000.", null);
    }
    return n.asInt();
  }
}
