package ai.msrv.gaterook.connectors.logs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Index names as people scope them: indices a date or a rollover counter tells apart collapse into one pattern
 * ({@code logs-app-2026.11.12}, {@code logs-app-2026.11.13} → {@code logs-app-*}); aliases and data streams keep their
 * names. Hidden and system indices (a leading dot) and names a log index pattern can't hold are left out.
 */
final class IndexPatterns {

  /** One pattern or name to offer, with what it stands for. */
  record Group(String pattern, Kind kind, int count) {
    String detail() {
      return switch (kind) {
        case INDICES -> LogsSuggestions.count(count, "index", "indices");
        case INDEX -> "index";
        case ALIAS -> "alias";
        case DATA_STREAM -> "data stream";
      };
    }

    boolean logs() {
      return pattern.contains("log");
    }
  }

  /** {@code INDICES}: a pattern over dated or rolled-over indices. */
  enum Kind { INDICES, INDEX, ALIAS, DATA_STREAM }

  /**
   * A name, a separator, then a date ({@code 2026.11.12}, {@code 2026-11}, {@code 20261112}, with an hour) or a
   * rollover counter ({@code 000001}), optionally followed by a counter: {@code filebeat-8.15.0-2026.11.12-000001}.
   */
  private static final Pattern DATED = Pattern.compile("(.+?[-_.])(?:\\d{4}[.\\-_]?\\d{2}(?:[.\\-_]?\\d{2})?"
      + "(?:[.\\-_t]?\\d{2}(?:[.\\-_]?\\d{2})?)?|\\d{6})(?:[-_.]\\d{1,6})?");

  private IndexPatterns() {}

  /** The pattern of one index name: {@code logs-2026.11.12} → {@code logs-*}; a name without a date stays as it is. */
  static String pattern(String name) {
    Matcher m = DATED.matcher(name);
    return m.matches() ? m.group(1) + "*" : name;
  }

  /**
   * The groups of a {@code _resolve/index} answer, the log-like ones first, then the larger, then by name.
   */
  static List<Group> of(JsonNode resolved) {
    Map<String, Group> out = new LinkedHashMap<>();
    for (JsonNode index : resolved.path("indices")) {
      String name = index.path("name").asString("");
      if (!usable(name) || index.has("data_stream")) {
        continue;
      }
      String pattern = pattern(name);
      Group before = out.get(pattern);
      int count = before == null ? 1 : before.count() + 1;
      out.put(pattern, new Group(pattern, pattern.equals(name) && before == null ? Kind.INDEX : Kind.INDICES, count));
    }
    for (JsonNode alias : resolved.path("aliases")) {
      String name = alias.path("name").asString("");
      if (usable(name)) {
        out.putIfAbsent(name, new Group(name, Kind.ALIAS, 1));
      }
    }
    for (JsonNode stream : resolved.path("data_streams")) {
      String name = stream.path("name").asString("");
      if (usable(name)) {
        out.putIfAbsent(name, new Group(name, Kind.DATA_STREAM, 1));
      }
    }
    List<Group> groups = new ArrayList<>(out.values());
    groups.sort(Comparator.comparing((Group g) -> !g.logs()).thenComparing(Group::count, Comparator.reverseOrder())
        .thenComparing(Group::pattern));
    return groups;
  }

  /**
   * What a {@code _resolve/index} answer names, hidden and system ones left out: {@code 42 indices, 3 aliases and 2
   * data streams}; {@code null} when it names nothing.
   */
  static String summary(JsonNode resolved) {
    List<String> parts = new ArrayList<>();
    int[] counts = new int[3];
    String[] names = {"indices", "aliases", "data_streams"};
    for (int i = 0; i < names.length; i++) {
      for (JsonNode e : resolved.path(names[i])) {
        if (!e.path("name").asString(".").startsWith(".") && !e.has("data_stream")) {
          counts[i]++;
        }
      }
    }
    if (counts[0] > 0) {
      parts.add(LogsSuggestions.count(counts[0], "index", "indices"));
    }
    if (counts[1] > 0) {
      parts.add(LogsSuggestions.count(counts[1], "alias", "aliases"));
    }
    if (counts[2] > 0) {
      parts.add(LogsSuggestions.count(counts[2], "data stream", "data streams"));
    }
    if (parts.isEmpty()) {
      return null;
    }
    return parts.size() == 1 ? parts.getFirst()
        : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
  }

  private static boolean usable(String name) {
    return Settings.INDEX.matcher(name).matches();
  }
}
