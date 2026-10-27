package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code redis_get} (report 05 §3.4): {@code TYPE}, {@code PTTL} and {@code MEMORY USAGE} in one round trip, then one
 * bounded read for the type — never a command whose cost grows with the value ({@code HGETALL}, {@code SMEMBERS},
 * {@code LRANGE 0 -1}). Binary API only: keys and values stay bytes until {@link RedisValues} decides how to show
 * them. A key that changed its type between {@code TYPE} and the read ({@code WRONGTYPE}) is read once more.
 */
final class RedisReads {

  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final int MAX_SCAN_ITERATIONS = 50;

  private RedisReads() {}

  /** What one read found: the structured answer and the notes for its text. */
  record Reading(ObjectNode structured, List<String> notes) {}

  static Reading get(RedisSession s, RedisRequest.Get r, Budget budget, ExecContext ctx, String connectionId)
      throws ConnectorException {
    Cursors.GetState from = r.cursor() == null ? null : Cursors.openGet(ctx, r.cursor(), connectionId, r.key());
    try {
      return read(s, r, budget, ctx, connectionId, from);
    } catch (JedisDataException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("WRONGTYPE")) {
        // The key was replaced by one of another type between TYPE and the read: once more, from TYPE.
        return read(s, r, budget, ctx, connectionId, from);
      }
      throw e;
    }
  }

  private static Reading read(RedisSession s, RedisRequest.Get r, Budget budget, ExecContext ctx,
      String connectionId, Cursors.GetState from) throws ConnectorException {
    long t0 = System.nanoTime();
    byte[] key = r.key();
    List<Object> head = s.batch(List.of(RedisSession.args(Cmd.TYPE, key), RedisSession.args(Cmd.PTTL, key),
        RedisSession.args(Cmd.MEMORY, "USAGE", key)));
    String type = Replies.text(orThrow(head.get(0)));
    long pttl = Replies.integer(orThrow(head.get(1)), -2);
    // MEMORY USAGE may be refused to a narrow ACL user or renamed on managed servers: then it's unknown.
    Long memory = head.get(2) instanceof Long l ? l : null;

    ObjectNode out = JSON.objectNode();
    List<String> notes = new ArrayList<>();
    out.put("key", r.keyText());
    if (r.base64()) {
      out.put("encoding", "base64");
    }
    if (type == null || type.equals("none") || pttl == -2) {
      if (from != null) {
        throw ConnectorException.badInput("The key is gone since the cursor was made. Call redis_get without "
            + "cursor.");
      }
      out.put("type", "none").put("exists", false).putNull("ttlMs").putNull("memoryBytes").putNull("length");
      out.put("returned", 0).put("truncated", false).putNull("nextCursor");
      return new Reading(finish(out, notes, t0), notes);
    }
    if (from != null && !from.type().equals(type)) {
      throw ConnectorException.badInput("The key changed its type since the cursor was made (" + from.type()
          + " → " + type + "). Call redis_get without cursor.");
    }
    out.put("type", type).put("exists", true);
    if (pttl >= 0) {
      out.put("ttlMs", pttl).put("persistent", false);
    } else {
      out.putNull("ttlMs").put("persistent", true);
    }
    if (memory == null) {
      out.putNull("memoryBytes");
    } else {
      out.put("memoryBytes", memory);
    }
    String position = from == null ? null : from.position();
    Page page = switch (type) {
      case "string" -> string(s, key, budget, notes);
      case "hash" -> scanned(s, Cmd.HSCAN, Cmd.HLEN, key, position, budget, notes, true);
      case "set" -> scanned(s, Cmd.SSCAN, Cmd.SCARD, key, position, budget, notes, false);
      case "list" -> list(s, key, position, budget);
      case "zset" -> zset(s, key, position, budget);
      case "stream" -> stream(s, key, position, budget);
      case "ReJSON-RL" -> json(s, key, memory, budget, notes);
      default -> unsupported(type, notes);
    };
    out.put("supported", page.value != null);
    if (page.length == null) {
      out.putNull("length");
    } else {
      out.put("length", page.length);
    }
    if (page.value != null) {
      out.set("value", page.value);
    }
    out.put("returned", page.returned).put("truncated", page.truncated);
    if (page.next == null) {
      out.putNull("nextCursor");
    } else {
      out.put("nextCursor", Cursors.sealGet(ctx, connectionId, key, new Cursors.GetState(type, page.next)));
    }
    return new Reading(finish(out, notes, t0), notes);
  }

  /** A read of one type: its value, how many elements, whether the budget cut it, and where the next page starts. */
  private record Page(JsonNode value, Long length, int returned, boolean truncated, String next) {}

  private static Page string(RedisSession s, byte[] key, Budget budget, List<String> notes)
      throws ConnectorException {
    long len = Replies.integer(s.call(Cmd.STRLEN, key), 0);
    long max = Math.min(s.settings().maxValueBytes(), budget.bytes());
    byte[] value;
    boolean truncated = false;
    if (len <= max) {
      value = Replies.bytes(s.call(Cmd.GET, key));
      if (value == null) {
        value = new byte[0];
      }
    } else {
      // Only the head: GET of a 200 MB value would block the server and fill the gateway's memory.
      byte[] head = Replies.bytes(s.call(Cmd.GETRANGE, key, 0, max - 1));
      value = RedisValues.withoutPartialCharacter(head == null ? new byte[0] : head);
      truncated = true;
      notes.add(String.format(Locale.US, "The value is %,d bytes; these are the first %,d.", len, value.length));
    }
    return new Page(RedisValues.value(value), len, 1, truncated, null);
  }

  /** HSCAN or SSCAN from {@code position} until {@code budget} or the end, whole batches only. */
  private static Page scanned(RedisSession s, Cmd scan, Cmd size, byte[] key, String position, Budget budget,
      List<String> notes, boolean hash) throws ConnectorException {
    long length = Replies.integer(s.call(size, key), 0);
    ObjectNode fields = JSON.objectNode();
    ArrayNode members = JSON.arrayNode();
    String cursor = position == null || position.isEmpty() ? "0" : position;
    int returned = 0;
    long bytes = 0;
    boolean truncated = false;
    boolean cutCell = false;
    for (int i = 0; i < MAX_SCAN_ITERATIONS; i++) {
      int want = Math.clamp(budget.elements() - returned, 1, 1_000);
      List<Object> reply = Replies.list(s.call(scan, key, cursor, "COUNT", want));
      String nextCursor = Replies.text(reply.isEmpty() ? null : reply.get(0));
      List<Object> items = Replies.list(reply.size() > 1 ? reply.get(1) : null);
      int step = hash ? 2 : 1;
      long batchBytes = 0;
      for (int j = 0; j + step - 1 < items.size(); j += step) {
        for (int k = 0; k < step; k++) {
          byte[] b = Replies.bytes(items.get(j + k));
          batchBytes += Math.min(b == null ? 0 : b.length, budget.cellBytes()) + Budget.OVERHEAD;
        }
      }
      int batchCount = items.size() / step;
      boolean fits = bytes + batchBytes <= budget.bytes() && returned + batchCount <= 2L * budget.elements();
      if (!fits && returned > 0) {
        // Stop before this batch: the next page starts from the cursor that returned it.
        truncated = true;
        break;
      }
      boolean[] cut = new boolean[1];
      int kept = 0;
      for (int j = 0; j + step - 1 < items.size(); j += step) {
        byte[] first = Replies.bytes(items.get(j));
        long cost = Math.min(first == null ? 0 : first.length, budget.cellBytes()) + Budget.OVERHEAD;
        byte[] second = hash ? Replies.bytes(items.get(j + 1)) : null;
        if (hash) {
          cost += Math.min(second == null ? 0 : second.length, budget.cellBytes()) + Budget.OVERHEAD;
        }
        if (!fits && bytes + cost > budget.bytes()) {
          break;
        }
        bytes += cost;
        if (hash) {
          fields.set(RedisValues.field(first == null ? new byte[0] : first),
              RedisValues.element(second == null ? new byte[0] : second, budget.cellBytes(), cut));
        } else {
          members.add(RedisValues.element(first == null ? new byte[0] : first, budget.cellBytes(), cut));
        }
        kept++;
      }
      cutCell |= cut[0];
      returned += kept;
      cursor = nextCursor == null ? "0" : nextCursor;
      if (!fits) {
        truncated = true;
        notes.add(String.format(Locale.US, "%,d %s of this page didn't fit the size limit; the cursor goes past "
            + "them.", batchCount - kept, hash ? "fields" : "members"));
        break;
      }
      if (cursor.equals("0") || returned >= budget.elements()) {
        break;
      }
    }
    if (cutCell) {
      truncated = true;
      notes.add("Values longer than " + budget.cellBytes() + " bytes are cut and end with ‹truncated N bytes›.");
    }
    String next = cursor.equals("0") ? null : cursor;
    if (next != null) {
      notes.add((hash ? "Fields" : "Members") + " may repeat across pages (Redis "
          + scan.wire() + " guarantee).");
    }
    return new Page(hash ? fields : members, length, returned, truncated || next != null, next);
  }

  private static Page list(RedisSession s, byte[] key, String position, Budget budget) throws ConnectorException {
    long length = Replies.integer(s.call(Cmd.LLEN, key), 0);
    long offset = offset(position);
    int n = budget.elements();
    List<Object> items = Replies.list(s.call(Cmd.LRANGE, key, offset, offset + n - 1));
    ArrayNode out = JSON.arrayNode();
    boolean[] cut = new boolean[1];
    long bytes = 0;
    int kept = 0;
    for (Object o : items) {
      byte[] b = Replies.bytes(o);
      long cost = Math.min(b == null ? 0 : b.length, budget.cellBytes()) + Budget.OVERHEAD;
      if (kept > 0 && bytes + cost > budget.bytes()) {
        break;
      }
      bytes += cost;
      out.add(RedisValues.element(b == null ? new byte[0] : b, budget.cellBytes(), cut));
      kept++;
    }
    long next = offset + kept;
    boolean more = next < length;
    return new Page(out, length, kept, more || cut[0], more ? Long.toString(next) : null);
  }

  private static Page zset(RedisSession s, byte[] key, String position, Budget budget) throws ConnectorException {
    long length = Replies.integer(s.call(Cmd.ZCARD, key), 0);
    long offset = offset(position);
    int n = budget.elements();
    List<Object> items = Replies.list(s.call(Cmd.ZRANGE, key, offset, offset + n - 1, "WITHSCORES"));
    ArrayNode out = JSON.arrayNode();
    boolean[] cut = new boolean[1];
    long bytes = 0;
    int kept = 0;
    for (int i = 0; i + 1 < items.size(); i += 2) {
      byte[] member = Replies.bytes(items.get(i));
      long cost = Math.min(member == null ? 0 : member.length, budget.cellBytes()) + 2L * Budget.OVERHEAD;
      if (kept > 0 && bytes + cost > budget.bytes()) {
        break;
      }
      bytes += cost;
      ObjectNode e = out.addObject();
      e.set("member", RedisValues.element(member == null ? new byte[0] : member, budget.cellBytes(), cut));
      e.set("score", score(Replies.text(items.get(i + 1))));
      kept++;
    }
    long next = offset + kept;
    boolean more = next < length;
    return new Page(out, length, kept, more || cut[0], more ? Long.toString(next) : null);
  }

  /** Newest entries first; the next page ends just before the oldest id returned. */
  private static Page stream(RedisSession s, byte[] key, String position, Budget budget) throws ConnectorException {
    long length = Replies.integer(s.call(Cmd.XLEN, key), 0);
    int n = budget.elements();
    String end = position == null || position.isEmpty() ? "+" : before(position);
    List<Object> entries = end == null ? List.of() : Replies.list(s.call(Cmd.XREVRANGE, key, end, "-", "COUNT", n));
    ArrayNode out = JSON.arrayNode();
    boolean[] cut = new boolean[1];
    long bytes = 0;
    int kept = 0;
    String last = null;
    for (Object o : entries) {
      List<Object> entry = Replies.list(o);
      String id = Replies.text(entry.isEmpty() ? null : entry.get(0));
      List<Object> kv = Replies.list(entry.size() > 1 ? entry.get(1) : null);
      long cost = Budget.OVERHEAD + (id == null ? 0 : id.length());
      for (Object x : kv) {
        byte[] b = Replies.bytes(x);
        cost += Math.min(b == null ? 0 : b.length, budget.cellBytes()) + Budget.OVERHEAD;
      }
      if (kept > 0 && bytes + cost > budget.bytes()) {
        break;
      }
      bytes += cost;
      ObjectNode e = out.addObject();
      e.put("id", id);
      ObjectNode fields = e.putObject("fields");
      for (int i = 0; i + 1 < kv.size(); i += 2) {
        byte[] f = Replies.bytes(kv.get(i));
        byte[] v = Replies.bytes(kv.get(i + 1));
        fields.set(RedisValues.field(f == null ? new byte[0] : f),
            RedisValues.element(v == null ? new byte[0] : v, budget.cellBytes(), cut));
      }
      last = id;
      kept++;
    }
    boolean more = last != null && (kept < entries.size() || entries.size() == n) && before(last) != null;
    return new Page(out, length, kept, more || cut[0], more ? last : null);
  }

  /** A RedisJSON document (Redis Stack, Redis 8): read whole only when its size is known and small enough. */
  private static Page json(RedisSession s, byte[] key, Long memory, Budget budget, List<String> notes)
      throws ConnectorException {
    long max = Math.min(s.settings().maxValueBytes(), budget.bytes());
    if (memory == null || memory > max) {
      notes.add(memory == null ? "The size of this JSON document is unknown (MEMORY USAGE isn't allowed), so it "
          + "isn't read." : String.format(Locale.US, "This JSON document takes %,d bytes, more than the %,d bytes "
          + "a call reads.", memory, max));
      return new Page(null, null, 0, true, null);
    }
    byte[] raw = Replies.bytes(s.call(Cmd.JSON_GET, key));
    if (raw == null) {
      return new Page(JSON.nullNode(), null, 0, false, null);
    }
    JsonNode value;
    try {
      value = MAPPER.readTree(raw);
    } catch (JacksonException e) {
      value = RedisValues.value(raw);
    }
    return new Page(value, null, 1, false, null);
  }

  private static Page unsupported(String type, List<String> notes) {
    notes.add("redis_get can't read keys of type " + type + " yet.");
    return new Page(null, null, 0, false, null);
  }

  /** The previous stream id: {@code (id} needs Redis 6.2, so the id is stepped back by one instead. */
  static String before(String id) {
    int dash = id.indexOf('-');
    try {
      long ms = Long.parseUnsignedLong(dash < 0 ? id : id.substring(0, dash));
      long seq = dash < 0 ? 0 : Long.parseUnsignedLong(id.substring(dash + 1));
      if (seq != 0) {
        return Long.toUnsignedString(ms) + "-" + Long.toUnsignedString(seq - 1);
      }
      return ms == 0 ? null : Long.toUnsignedString(ms - 1) + "-" + Long.toUnsignedString(-1L);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static long offset(String position) throws ConnectorException {
    if (position == null || position.isEmpty()) {
      return 0;
    }
    try {
      long v = Long.parseLong(position);
      if (v < 0) {
        throw new NumberFormatException();
      }
      return v;
    } catch (NumberFormatException e) {
      throw ConnectorException.badInput("The cursor is damaged. Call redis_get without cursor.");
    }
  }

  private static JsonNode score(String text) {
    if (text == null) {
      return JSON.nullNode();
    }
    try {
      double d = Double.parseDouble(text);
      if (Double.isFinite(d)) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? JSON.numberNode((long) d) : JSON.numberNode(d);
      }
    } catch (NumberFormatException ignored) {
      // inf, -inf: kept as Redis wrote them.
    }
    return JSON.stringNode(text);
  }

  /** A reply of a batch, or the error the server gave for it. */
  private static Object orThrow(Object reply) {
    if (reply instanceof JedisAccessControlException e) {
      throw e;
    }
    if (reply instanceof JedisDataException e) {
      throw e;
    }
    return reply;
  }

  private static ObjectNode finish(ObjectNode out, List<String> notes, long t0) {
    if (!notes.isEmpty()) {
      ArrayNode n = out.putArray("notes");
      notes.forEach(n::add);
    }
    out.put("elapsedMs", (System.nanoTime() - t0) / 1_000_000);
    return out;
  }

  /** Text of a key for an answer: the key as given, or its bytes quoted. */
  static String display(byte[] key) {
    return RedisValues.utf8(key).orElseGet(() -> RedisValues.quote(key));
  }

  static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /** {@code TYPE} and {@code PTTL} of each key, one round trip (report 05 §3.5 {@code details}). */
  static List<Object[]> typesAndTtls(RedisSession s, List<byte[]> keys) throws ConnectorException {
    List<Object[]> out = new ArrayList<>(keys.size());
    for (int from = 0; from < keys.size(); from += 250) {
      List<byte[]> part = keys.subList(from, Math.min(keys.size(), from + 250));
      List<CommandArguments> batch = new ArrayList<>(part.size() * 2);
      for (byte[] k : part) {
        batch.add(RedisSession.args(Cmd.TYPE, k));
        batch.add(RedisSession.args(Cmd.PTTL, k));
      }
      List<Object> replies = s.batch(batch);
      for (int i = 0; i < part.size(); i++) {
        Object type = replies.get(2 * i);
        Object ttl = replies.get(2 * i + 1);
        out.add(new Object[] {type instanceof JedisDataException ? null : Replies.text(type),
            ttl instanceof Long l ? l : null});
      }
    }
    return out;
  }
}
