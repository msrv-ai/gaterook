package ai.msrv.gaterook.connectors.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * The text of an answer, for clients that read text rather than {@code structuredContent}: a summary line, the value
 * as compact JSON (or one line per key), the notes and how to go on. It says the same as the structured content —
 * the masker treats both alike.
 */
final class RedisText {

  private RedisText() {}

  static String get(JsonNode s, int database) {
    String key = RedisValues.quote(s.path("key").asString(""));
    if (!s.path("exists").asBoolean(false)) {
      return key + " doesn't exist in database " + database + ".";
    }
    String type = s.path("type").asString("");
    List<String> parts = new ArrayList<>();
    parts.add(type + " " + key);
    if (s.path("length").isNumber()) {
      parts.add(String.format(Locale.US, "%,d %s", s.path("length").asLong(), unit(type, s.path("length").asLong())));
    }
    parts.add(s.path("ttlMs").isNumber() ? "expires in " + ttl(s.path("ttlMs").asLong()) : "persistent");
    if (s.path("memoryBytes").isNumber()) {
      parts.add(String.format(Locale.US, "%,d bytes in memory", s.path("memoryBytes").asLong()));
    }
    parts.add(s.path("elapsedMs").asLong() + " ms");
    StringBuilder out = new StringBuilder(String.join(" · ", parts));
    if (!s.path("supported").asBoolean(true)) {
      out.append("\nredis_get can't read keys of type ").append(type).append(" yet.");
    } else if (s.has("value")) {
      out.append('\n').append(s.path("value").toString());
    }
    notes(out, s);
    if (s.path("nextCursor").isString()) {
      out.append("\nNext: call redis_get again with the same key and cursor ").append(s.path("nextCursor").asString())
          .append('.');
    }
    return out.toString();
  }

  static String scan(JsonNode s) {
    int n = s.path("keys").size();
    boolean complete = s.path("complete").asBoolean(true);
    StringBuilder out = new StringBuilder(String.format(Locale.US, "%,d key%s match%s %s", n, n == 1 ? "" : "s",
        n == 1 ? "es" : "", RedisValues.quote(s.path("pattern").asString("*"))));
    if (s.path("type").isString()) {
      out.append(" of type ").append(s.path("type").asString());
    }
    out.append(String.format(Locale.US, " (%d SCAN round%s%s)", s.path("iterations").asInt(),
        s.path("iterations").asInt() == 1 ? "" : "s", complete ? ", scan complete" : ", more to scan"));
    for (JsonNode k : s.path("keys")) {
      out.append('\n').append(k.path("encoding").isString() ? "base64:" : "").append(k.path("key").asString(""));
      if (k.path("type").isString()) {
        out.append("  ").append(k.path("type").asString());
      }
      if (k.has("ttlMs")) {
        out.append("  ").append(k.path("ttlMs").isNumber() ? "ttl " + ttl(k.path("ttlMs").asLong()) : "persistent");
      }
    }
    int hidden = s.path("hiddenByPolicy").asInt();
    if (hidden > 0) {
      out.append(String.format(Locale.US, "\n%,d key%s hidden by the connection's key lists.", hidden,
          hidden == 1 ? "" : "s"));
    }
    notes(out, s);
    if (s.path("nextCursor").isString()) {
      out.append("\nNext: call redis_scan again with the same pattern and type and cursor ")
          .append(s.path("nextCursor").asString()).append('.');
    }
    return out.toString();
  }

  private static void notes(StringBuilder out, JsonNode s) {
    for (JsonNode n : s.path("notes")) {
      out.append('\n').append(n.asString(""));
    }
  }

  private static String unit(String type, long n) {
    String one = switch (type) {
      case "string" -> "byte";
      case "hash" -> "field";
      case "stream" -> "entry";
      default -> "element";
    };
    if (n == 1) {
      return one;
    }
    return one.equals("entry") ? "entries" : one + "s";
  }

  /** {@code 59m59s}, {@code 2h0m}, {@code 450 ms}. */
  static String ttl(long ms) {
    if (ms < 1_000) {
      return ms + " ms";
    }
    Duration d = Duration.ofMillis(ms);
    if (d.toDays() > 0) {
      return d.toDays() + "d" + d.toHoursPart() + "h";
    }
    if (d.toHours() > 0) {
      return d.toHours() + "h" + d.toMinutesPart() + "m";
    }
    if (d.toMinutes() > 0) {
      return d.toMinutes() + "m" + d.toSecondsPart() + "s";
    }
    return d.toSeconds() + "s";
  }
}
