package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cursors of {@code logs_search} and {@code logs_tail} (report 07 §3, §7): {@code lc1.} + a handle sealed by the
 * core's HMAC codec, so an agent can carry one back but can't forge, edit or reuse one for another connection or
 * query. No session lives on the gateway: everything the next call needs is in the cursor.
 *
 * <p>A cursor is a boundary in time plus the entries already handed out <em>at</em> that boundary: backends store
 * several entries at one timestamp (two Loki lines with the same nanosecond, a burst in one CloudWatch millisecond),
 * and a page may end between them. The next call reads from the boundary inclusively and skips exactly those
 * entries — nothing is lost, nothing comes twice. At most {@value #MAX_SEEN} are remembered; a boundary with more
 * entries than that is stepped over and the answer says {@code gapPossible}.
 */
final class Cursors {

  /** The prefix of every Logs cursor; the masker's entropy check knows the core's handles by it. */
  static final String PREFIX = "lc1.";
  /** Entries remembered at the boundary: 6 bytes each, so a cursor stays well inside the tools' 4 KB. */
  static final int MAX_SEEN = 256;
  /** A tail cursor older than this restarts an hour back, with {@code gapPossible}. */
  static final Duration TAIL_HORIZON = Duration.ofHours(1);
  private static final Duration TTL = Duration.ofDays(1);
  private static final String SEARCH = "logs.search";
  private static final String TAIL = "logs.tail";

  private Cursors() {}

  /**
   * Where a search continues: entries in {@code [fromNs, toNs]}, minus {@code seen} at {@code toNs}; with
   * {@code continuation}, the backend resumes this exact window with its own token.
   */
  record SearchState(long fromNs, long toNs, Set<Long> seen, String continuation) {}

  /** Where a tail continues: entries from {@code lastNs} on, minus {@code seen} at {@code lastNs}. */
  record TailState(long lastNs, Set<Long> seen) {}

  static String seal(ExecContext ctx, String connectionId, String queryKey, SearchState s) {
    ObjectNode d = base(connectionId, queryKey);
    d.put("f", s.fromNs());
    d.put("t", s.toNs());
    d.put("s", seen(s.seen()));
    if (s.continuation() != null) {
      d.put("p", s.continuation());
    }
    return PREFIX + ctx.handles().seal(SEARCH, d, TTL);
  }

  static String seal(ExecContext ctx, String connectionId, String queryKey, TailState s) {
    ObjectNode d = base(connectionId, queryKey);
    d.put("t", s.lastNs());
    d.put("s", seen(s.seen()));
    return PREFIX + ctx.handles().seal(TAIL, d, TTL);
  }

  static SearchState openSearch(ExecContext ctx, String cursor, String connectionId, String queryKey)
      throws ConnectorException {
    JsonNode d = open(ctx, SEARCH, cursor, connectionId, queryKey, "logs_search");
    return new SearchState(d.path("f").asLong(), d.path("t").asLong(), seen(d.path("s").asString("")),
        d.path("p").isString() ? d.path("p").asString() : null);
  }

  static TailState openTail(ExecContext ctx, String cursor, String connectionId, String queryKey)
      throws ConnectorException {
    JsonNode d = open(ctx, TAIL, cursor, connectionId, queryKey, "logs_tail");
    return new TailState(d.path("t").asLong(), seen(d.path("s").asString("")));
  }

  /**
   * What a cursor is bound to: the tool, the backend, the query and the source. A cursor of another query would page
   * through entries the agent never asked for, quietly.
   */
  static String queryKey(LogsRequest r, Settings s) {
    String text = r.tool().id + "\n" + s.backend() + "\n" + r.query() + "\n" + (r.source() == null ? "" : r.source());
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ObjectNode base(String connectionId, String queryKey) {
    ObjectNode d = JsonNodeFactory.instance.objectNode();
    d.put("v", 1);
    d.put("c", connectionId);
    d.put("q", queryKey);
    return d;
  }

  private static JsonNode open(ExecContext ctx, String purpose, String cursor, String connectionId, String queryKey,
      String tool) throws ConnectorException {
    if (cursor == null || !cursor.startsWith(PREFIX)) {
      throw ConnectorException.badInput("This isn't a cursor of " + tool + ": pass nextCursor exactly as you got "
          + "it, or call again without cursor.");
    }
    Optional<JsonNode> opened = ctx.handles().open(purpose, cursor.substring(PREFIX.length()));
    if (opened.isEmpty() || opened.get().path("v").asInt() != 1) {
      throw ConnectorException.badInput("The cursor is invalid or has expired (cursors last 24 h). Call " + tool
          + " again without cursor.");
    }
    JsonNode d = opened.get();
    if (!connectionId.equals(d.path("c").asString(null)) || !queryKey.equals(d.path("q").asString(null))) {
      throw ConnectorException.badInput("This cursor belongs to another query or connection. Call " + tool
          + " again without cursor, or repeat the query and source the cursor came with.");
    }
    return d;
  }

  private static String seen(Set<Long> keys) {
    ByteBuffer b = ByteBuffer.allocate(keys.size() * 6);
    for (long k : keys) {
      for (int i = 5; i >= 0; i--) {
        b.put((byte) (k >>> (8 * i)));
      }
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());
  }

  private static Set<Long> seen(String text) throws ConnectorException {
    byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The cursor is damaged. Call again without cursor.");
    }
    Set<Long> out = new LinkedHashSet<>();
    for (int at = 0; at + 6 <= bytes.length; at += 6) {
      long k = 0;
      for (int i = 0; i < 6; i++) {
        k = (k << 8) | (bytes[at + i] & 0xFF);
      }
      out.add(k);
    }
    return out;
  }
}
