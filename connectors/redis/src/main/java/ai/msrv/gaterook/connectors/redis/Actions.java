package ai.msrv.gaterook.connectors.redis;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The exact action of a call, for a person who set a Redis tool to Approval (report 05 §3.6): the commands as they go
 * to the server, keys quoted the way {@code redis-cli} prints them — one spelling per byte, so nothing invisible or
 * look-alike hides in a key. The payload is the canonical request, which is what runs after the approval.
 */
final class Actions {

  private Actions() {}

  static ActionPreview preview(RedisRequest r, RedisSettings s, ConnectionConfig cfg) {
    String target = cfg.name() + " · " + s.hostPort() + "/" + s.database();
    return switch (r) {
      case RedisRequest.Get g -> get(g, s, cfg, target);
      case RedisRequest.Scan sc -> scan(sc, cfg, target);
    };
  }

  private static ActionPreview get(RedisRequest.Get g, RedisSettings s, ConnectionConfig cfg, String target) {
    String k = RedisValues.quote(g.key());
    int n = g.limit();
    String from = g.cursor() == null ? "0" : "<cursor>";
    String offset = g.cursor() == null ? "0" : "<cursor>";
    List<String> lines = new ArrayList<>(List.of(
        "TYPE " + k,
        "PTTL " + k,
        "MEMORY USAGE " + k,
        "then one read for its type:",
        "  string  STRLEN " + k + "; GET " + k + " (GETRANGE " + k + " 0 " + (s.maxValueBytes() - 1)
            + " when it is longer)",
        "  hash    HLEN " + k + "; HSCAN " + k + " " + from + " COUNT " + n,
        "  list    LLEN " + k + "; LRANGE " + k + " " + offset + " " + (g.cursor() == null ? n - 1 : "<cursor+" + (n - 1)
            + ">"),
        "  set     SCARD " + k + "; SSCAN " + k + " " + from + " COUNT " + n,
        "  zset    ZCARD " + k + "; ZRANGE " + k + " " + offset + " " + (g.cursor() == null ? n - 1 : "<cursor+"
            + (n - 1) + ">") + " WITHSCORES",
        "  stream  XLEN " + k + "; XREVRANGE " + k + " " + (g.cursor() == null ? "+" : "<cursor>") + " - COUNT " + n));
    String summary = shorten("redis_get " + k + " · " + cfg.name() + " (" + cfg.env().id() + ")");
    return ActionPreview.of(ActionPreview.Kind.COMMAND, "text", String.join("\n", lines), target, summary)
        .withFields(List.of(new ActionPreview.Field("Key", k),
            new ActionPreview.Field("At most", String.format(Locale.US, "%,d elements (the row limit may lower it)",
                n))))
        .withFacets(Map.of("redis.key", RedisReads.display(g.key())))
        .withPayload(g.payload());
  }

  private static ActionPreview scan(RedisRequest.Scan sc, ConnectionConfig cfg, String target) {
    StringBuilder first = new StringBuilder("SCAN ").append(sc.cursor() == null ? "0" : "<cursor>");
    if (!sc.pattern().equals("*")) {
      first.append(" MATCH ").append(RedisValues.quote(sc.pattern()));
    }
    first.append(" COUNT ").append(Math.clamp(sc.count(), 10, 1_000));
    if (sc.type() != null) {
      first.append(" TYPE ").append(sc.type());
    }
    List<String> lines = new ArrayList<>(List.of(first.toString(),
        String.format(Locale.US, "again with the returned cursor until %,d keys, the end, %d rounds or %d s",
            sc.count(), ScanLoop.MAX_ITERATIONS, ScanLoop.SCAN_BUDGET.toSeconds())));
    if (sc.details()) {
      lines.add("then TYPE and PTTL of each key returned");
    }
    String summary = shorten("redis_scan " + RedisValues.quote(sc.pattern()) + " · " + cfg.name() + " ("
        + cfg.env().id() + ")");
    return ActionPreview.of(ActionPreview.Kind.COMMAND, "text", String.join("\n", lines), target, summary)
        .withFields(List.of(new ActionPreview.Field("Pattern", RedisValues.quote(sc.pattern())),
            new ActionPreview.Field("At most", String.format(Locale.US, "%,d keys (the row limit may lower it)",
                sc.count()))))
        .withFacets(Map.of("redis.pattern", sc.pattern()))
        .withPayload(sc.payload());
  }

  private static String shorten(String s) {
    return s.length() <= 120 ? s : s.substring(0, 119) + "…";
  }
}
