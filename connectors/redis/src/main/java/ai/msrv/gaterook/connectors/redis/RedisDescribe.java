package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redis' part of {@code describe_connection} (report 05 §3.7): {@code HELLO} (server, version, mode, role, modules),
 * {@code DBSIZE}, the database and a histogram of key prefixes (up to the first {@code :}) over the first 1,000 keys
 * {@code SCAN} returns — only keys the connection lets agents read, the others are counted, not named. {@code object}
 * narrows the histogram to keys matching a glob.
 */
final class RedisDescribe {

  static final int SAMPLE = 1_000;
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private RedisDescribe() {}

  static ObjectNode describe(RedisSession s, String object) throws ConnectorException {
    RedisSettings settings = s.settings();
    Map<String, Object> hello = Replies.pairs(s.call(Cmd.HELLO, "2"));
    ObjectNode out = JSON.objectNode();
    out.put("server", Replies.text(hello.get("server")));
    out.put("version", Replies.text(hello.get("version")));
    out.put("mode", Replies.text(hello.get("mode")));
    out.put("role", Replies.text(hello.get("role")));
    ArrayNode modules = out.putArray("modules");
    for (Object m : Replies.list(hello.get("modules"))) {
      Object name = Replies.pairs(m).get("name");
      if (name != null) {
        modules.add(Replies.text(name));
      }
    }
    out.put("database", settings.database());
    out.put("keys", Replies.integer(s.call(Cmd.DBSIZE), -1));

    String pattern = object == null || object.isBlank() ? "*" : object;
    if (pattern.length() > RedisSettings.MAX_PATTERN || RedisSettings.hasControl(pattern)) {
      throw ConnectorException.badInput("The object to describe is a Redis glob of up to " + RedisSettings.MAX_PATTERN
          + " characters, like cache:*.");
    }
    Map<String, Integer> prefixes = new HashMap<>();
    int sampled = 0;
    int hidden = 0;
    String cursor = "0";
    int rounds = 0;
    do {
      List<Object> reply = Replies.list(s.call(Cmd.SCAN, ScanLoop.scanArgs(cursor, pattern, null, 500)));
      cursor = Replies.text(reply.isEmpty() ? null : reply.get(0));
      for (Object o : Replies.list(reply.size() > 1 ? reply.get(1) : null)) {
        byte[] k = Replies.bytes(o);
        if (k == null || sampled + hidden >= SAMPLE) {
          continue;
        }
        if (!settings.keyAllowed(k)) {
          hidden++;
          continue;
        }
        sampled++;
        prefixes.merge(prefix(k), 1, Integer::sum);
      }
      rounds++;
    } while (cursor != null && !cursor.equals("0") && sampled + hidden < SAMPLE && rounds < ScanLoop.MAX_ITERATIONS);
    ArrayNode histogram = out.putArray("prefixes");
    prefixes.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
        .limit(50)
        .forEach(e -> histogram.addObject().put("prefix", e.getKey()).put("keys", e.getValue()));
    out.put("pattern", pattern);
    out.put("sampled", sampled);
    out.put("hiddenByPolicy", hidden);
    if (!settings.keyAllowlist().isEmpty()) {
      ArrayNode allow = out.putArray("keyAllowlist");
      settings.keyAllowlist().forEach(g -> allow.add(g.pattern()));
    }
    if (!settings.keyDenylist().isEmpty()) {
      ArrayNode deny = out.putArray("keyDenylist");
      settings.keyDenylist().forEach(g -> deny.add(g.pattern()));
    }
    out.put("reads", "redis_get reads one key (string, hash, list, set, zset, stream), a page at a time; "
        + "redis_scan lists keys matching a Redis glob, with type and TTL. Nothing writes.");
    return out;
  }

  /** {@code cache:} of {@code cache:users:7}; a key without {@code :} is its own prefix. */
  static String prefix(byte[] key) {
    int i = 0;
    while (i < key.length && key[i] != ':') {
      i++;
    }
    byte[] head = java.util.Arrays.copyOf(key, i < key.length ? i + 1 : i);
    return RedisValues.utf8(head).orElseGet(() -> RedisValues.quote(head));
  }

  static String prefix(String key) {
    return prefix(key.getBytes(StandardCharsets.UTF_8));
  }
}
