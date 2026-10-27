package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cursors of {@code redis_get} ({@code rgc.}) and {@code redis_scan} ({@code rsc.}), report 05 §3.4–3.5: the prefix
 * plus a handle sealed by the core's HMAC codec, so an agent carries one back but can't forge, edit or move one to
 * another connection, key or pattern. Inside: the connection, what the cursor belongs to (a hash of the key and its
 * type, or a hash of the pattern and the type filter) and where to go on — Redis' own cursor, an offset or a stream
 * id. Hashes keep a cursor within the tools' 512 characters whatever the key or pattern, and name neither.
 *
 * <p>{@code classify} has no codec; it reads the payload without checking the MAC ({@link #peek}) only to deny a
 * cursor of another key or connection early with a clear text. {@code execute} opens it properly.
 */
final class Cursors {

  static final String GET_PREFIX = "rgc.";
  static final String SCAN_PREFIX = "rsc.";
  static final String GET = "redis.get";
  static final String SCAN = "redis.scan";
  private static final Duration TTL = Duration.ofDays(1);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private Cursors() {}

  /** Where {@code redis_get} goes on: {@code position} is an HSCAN/SSCAN cursor, an offset or a stream id. */
  record GetState(String type, String position) {}

  /** Where {@code redis_scan} goes on: Redis' SCAN cursor for this pattern and type. */
  record ScanState(String redisCursor) {}

  static String sealGet(ExecContext ctx, String connectionId, byte[] key, GetState s) {
    ObjectNode d = base(connectionId);
    d.put("k", keyHash(key));
    d.put("t", s.type());
    d.put("o", s.position());
    return GET_PREFIX + ctx.handles().seal(GET, d, TTL);
  }

  static String sealScan(ExecContext ctx, String connectionId, String pattern, String type, ScanState s) {
    ObjectNode d = base(connectionId);
    d.put("p", keyHash(pattern));
    if (type != null) {
      d.put("t", type);
    }
    d.put("r", s.redisCursor());
    return SCAN_PREFIX + ctx.handles().seal(SCAN, d, TTL);
  }

  static GetState openGet(ExecContext ctx, String cursor, String connectionId, byte[] key) throws ConnectorException {
    JsonNode d = open(ctx, GET, GET_PREFIX, cursor, "redis_get");
    if (!connectionId.equals(d.path("c").asString(null)) || !keyHash(key).equals(d.path("k").asString(null))) {
      throw ConnectorException.badInput(OTHER_KEY);
    }
    return new GetState(d.path("t").asString(""), d.path("o").asString(""));
  }

  static ScanState openScan(ExecContext ctx, String cursor, String connectionId, String pattern, String type)
      throws ConnectorException {
    JsonNode d = open(ctx, SCAN, SCAN_PREFIX, cursor, "redis_scan");
    if (!connectionId.equals(d.path("c").asString(null)) || !keyHash(pattern).equals(d.path("p").asString(null))
        || !java.util.Objects.equals(type, d.path("t").isString() ? d.path("t").asString() : null)) {
      throw ConnectorException.badInput(OTHER_PATTERN);
    }
    return new ScanState(d.path("r").asString("0"));
  }

  static final String OTHER_KEY = "This cursor belongs to another key or connection. Call redis_get without cursor.";
  static final String OTHER_PATTERN = "This cursor belongs to another pattern, type or connection. Call redis_scan "
      + "with the pattern and type the cursor came with, or without cursor.";

  /**
   * Why a cursor can't be this call's, judged from its unchecked payload ({@code classify}, no I/O, no codec); empty
   * when it might be. A forged payload passes here and fails the MAC in {@code execute}.
   */
  static Optional<String> peekGet(String cursor, String connectionId, byte[] key) {
    Optional<JsonNode> d = peek(cursor, GET_PREFIX);
    if (d.isEmpty()) {
      return Optional.of(NOT_A_CURSOR + "redis_get.");
    }
    if (!connectionId.equals(d.get().path("c").asString(null)) || !keyHash(key).equals(d.get().path("k")
        .asString(null))) {
      return Optional.of(OTHER_KEY);
    }
    return Optional.empty();
  }

  static Optional<String> peekScan(String cursor, String connectionId, String pattern, String type) {
    Optional<JsonNode> d = peek(cursor, SCAN_PREFIX);
    if (d.isEmpty()) {
      return Optional.of(NOT_A_CURSOR + "redis_scan.");
    }
    JsonNode n = d.get();
    if (!connectionId.equals(n.path("c").asString(null)) || !keyHash(pattern).equals(n.path("p").asString(null))
        || !java.util.Objects.equals(type, n.path("t").isString() ? n.path("t").asString() : null)) {
      return Optional.of(OTHER_PATTERN);
    }
    return Optional.empty();
  }

  private static final String NOT_A_CURSOR = "This isn't a cursor of ";

  /** The payload of a sealed handle ({@code base64url(JSON).base64url(MAC)}) read without its MAC. */
  private static Optional<JsonNode> peek(String cursor, String prefix) {
    if (cursor == null || !cursor.startsWith(prefix)) {
      return Optional.empty();
    }
    String handle = cursor.substring(prefix.length());
    int dot = handle.indexOf('.');
    String body = dot < 0 ? handle : handle.substring(0, dot);
    try {
      JsonNode sealed = JSON.readTree(Base64.getUrlDecoder().decode(body));
      // SealedHandles: {"p": purpose, "x": expiry, "d": payload}.
      JsonNode d = sealed.path("d");
      return d.isObject() && d.path("v").asInt() == 1 ? Optional.of(d) : Optional.empty();
    } catch (IllegalArgumentException | JacksonException e) {
      return Optional.empty();
    }
  }

  private static JsonNode open(ExecContext ctx, String purpose, String prefix, String cursor, String tool)
      throws ConnectorException {
    if (cursor == null || !cursor.startsWith(prefix)) {
      throw ConnectorException.badInput(NOT_A_CURSOR + tool + ": pass nextCursor exactly as you got it, or call "
          + "again without cursor.");
    }
    Optional<JsonNode> opened = ctx.handles().open(purpose, cursor.substring(prefix.length()));
    if (opened.isEmpty() || opened.get().path("v").asInt() != 1) {
      throw ConnectorException.badInput("The cursor is invalid or has expired (cursors last 24 h). Call " + tool
          + " again without cursor.");
    }
    return opened.get();
  }

  private static ObjectNode base(String connectionId) {
    ObjectNode d = JsonNodeFactory.instance.objectNode();
    d.put("v", 1);
    d.put("c", connectionId);
    return d;
  }

  /** 64 bits of SHA-256 of a key or pattern: enough to tell them apart, nothing to read them back from. */
  static String keyHash(byte[] key) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(key);
      return HexFormat.of().formatHex(h, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String keyHash(String key) {
    return keyHash(key.getBytes(StandardCharsets.UTF_8));
  }
}
