package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolCall;
import ai.msrv.gaterook.connectors.support.TimeArg;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Validated, immutable scan filters. Parsing and matching use only masked values and perform no I/O. */
record SshLogQuery(List<String> files, boolean tail, int maxLines, long scanBytes, long offset, String queryHash,
    Instant from, Instant to, SshLogParser parser, String needle, boolean caseSensitive, Pattern expression,
    Set<String> levels) {
  private static final long MIB = 1024 * 1024;
  private static final long MAX_OFFSET = 9_007_199_254_740_991L;

  static SshLogQuery parse(ToolCall call, SshLogSettings settings, Limits limits, Instant anchor)
      throws ConnectorException {
    JsonNode args = call.arguments();
    String file = text(args, "file", 4096);
    List<String> files = new ArrayList<>();
    if (file != null && args.has("files")) {
      throw ConnectorException.badInput("Choose file or files, not both.");
    }
    if (file != null) {
      settings.allowed(file);
      files.add(file);
    } else if (args.has("files")) {
      JsonNode array = args.path("files");
      if (!array.isArray() || array.isEmpty() || array.size() > 500) {
        throw ConnectorException.badInput("Choose between 1 and 500 selected log files.");
      }
      for (JsonNode item : array) {
        if (!item.isString()) throw ConnectorException.badInput("File names must be strings.");
        settings.allowed(item.asString());
        if (files.contains(item.asString())) throw ConnectorException.badInput("Choose each log file only once.");
        files.add(item.asString());
      }
    } else {
      if (SshLogsConnector.READ.equals(call.tool())) {
        throw ConnectorException.badInput("Choose the log file to read.");
      }
      files.addAll(settings.allowedFiles());
    }
    if (files.isEmpty()) throw ConnectorException.badInput("Select log files in the connection settings first.");
    Set<String> chosen = Set.copyOf(files);
    files = settings.allowedFiles().stream().filter(chosen::contains).toList();
    boolean tail = bool(args, "tail", false);
    int maxLines = (int) integer(args, "maxLines", 200, 1, 1000);
    maxLines = Math.min(maxLines, Math.max(1, limits == null ? 1000 : limits.maxLines()));
    long scanBytes = integer(args, "scanBytes", 16 * MIB, MIB, 64 * MIB);
    long offset = integer(args, "offset", 0, 0, MAX_OFFSET);
    if (offset != 0 && (tail || args.has("cursor") || files.size() > 1)) {
      throw ConnectorException.badInput("A byte offset is only available for one forward file without a cursor.");
    }

    String query = text(args, "query", 1024);
    boolean caseSensitive = bool(args, "caseSensitive", true);
    String mode = text(args, "matchMode", 16);
    if (mode == null) mode = "literal";
    if (!mode.equals("literal") && !mode.equals("regex")) {
      throw ConnectorException.badInput("matchMode must be literal or regex.");
    }
    Pattern expression = null;
    String needle = query == null || query.isEmpty() ? null : query;
    if (needle != null && mode.equals("regex")) {
      try {
        expression = Pattern.compile(needle, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
      } catch (PatternSyntaxException e) {
        throw ConnectorException.badInput("Invalid RE2 regular expression. Lookaround and backreferences are not supported.");
      }
    } else if (needle != null && !caseSensitive) {
      needle = needle.toLowerCase(Locale.ROOT);
    }
    Set<String> levels = new TreeSet<>();
    if (args.has("levels")) {
      JsonNode selected = args.path("levels");
      if (!selected.isArray() || selected.size() > 12) {
        throw ConnectorException.badInput("levels must be an array of log severity names.");
      }
      for (JsonNode item : selected) {
        String level = item.isString() && item.asString().length() <= 32
            ? SshLogParser.normalizeLevel(item.asString()) : null;
        if (level == null) {
          throw ConnectorException.badInput("Use TRACE, DEBUG, INFO, NOTICE, WARN, ERROR or FATAL log levels.");
        }
        levels.add(level);
      }
    }
    String start = text(args, "from", 80);
    String end = text(args, "to", 80);
    Instant from = null;
    Instant to = null;
    if (start != null || end != null) {
      if ((start != null && start.isBlank()) || (end != null && end.isBlank())) {
        throw ConnectorException.badInput("Time filters must contain an offset timestamp or relative time.");
      }
      Duration maxRange = limits == null ? Duration.ofHours(168) : limits.maxRange();
      if (maxRange == null || maxRange.isNegative() || maxRange.isZero()) maxRange = Duration.ofHours(1);
      try {
        to = end == null ? anchor : TimeArg.parse(end, anchor);
        from = start == null ? to.minus(maxRange) : TimeArg.parse(start, anchor);
        if (!from.isBefore(to)) throw ConnectorException.badInput("from must be earlier than to.");
        if (Duration.between(from, to).compareTo(maxRange) > 0) {
          throw ConnectorException.badInput("The time range exceeds this connection's maximum range. Narrow from/to.");
        }
      } catch (TimeArg.InvalidTimeException | DateTimeException | ArithmeticException e) {
        throw ConnectorException.badInput("Invalid log time range. Use ISO-8601 with an offset or a relative time such as -1h.");
      }
    }
    SshLogParser parser = SshLogParser.of(settings);
    var fingerprint = JsonNodeFactory.instance.objectNode();
    var selected = fingerprint.putArray("files");
    files.forEach(selected::add);
    fingerprint.put("tail", tail).put("maxLines", maxLines).put("scanBytes", scanBytes)
        .put("query", query == null ? "" : query).put("matchMode", mode).put("caseSensitive", caseSensitive)
        .put("from", from == null ? null : from.toString()).put("to", to == null ? null : to.toString());
    var severity = fingerprint.putArray("levels");
    levels.forEach(severity::add);
    fingerprint.set("parsing", settings.logsConfig());
    // The cursor supplies the continuation offset; bind the filters and the fixed relative-time anchor instead.
    String hash = sha256(fingerprint.toString());
    return new SshLogQuery(List.copyOf(files), tail, maxLines, scanBytes, offset, hash, from, to, parser,
        needle, caseSensitive, expression, Set.copyOf(levels));
  }

  SshLogParser.Parsed parseRecord(String maskedText) {
    return parser.parse(maskedText);
  }

  String maskRecord(String rawText, ExecContext ctx) {
    return parser.mask(rawText, ctx);
  }

  boolean timeFiltered() {
    return from != null;
  }

  boolean matches(SshLogParser.Parsed record) {
    if (timeFiltered() && (record.timestamp() == null || record.timestamp().isBefore(from)
        || !record.timestamp().isBefore(to))) return false;
    if (!levels.isEmpty() && (record.level() == null || !levels.contains(record.level()))) return false;
    if (needle == null) return true;
    if (expression != null) return expression.matcher(record.text()).find();
    String text = caseSensitive ? record.text() : record.text().toLowerCase(Locale.ROOT);
    return text.contains(needle);
  }

  private static String text(JsonNode args, String name, int max) throws ConnectorException {
    JsonNode value = args.get(name);
    if (value == null) return null;
    if (!value.isString() || value.asString().length() > max) {
      throw ConnectorException.badInput(name + " must be a string of at most " + max + " characters.");
    }
    return value.asString();
  }

  private static boolean bool(JsonNode args, String name, boolean fallback) throws ConnectorException {
    JsonNode value = args.get(name);
    if (value == null) return fallback;
    if (!value.isBoolean()) throw ConnectorException.badInput(name + " must be true or false.");
    return value.asBoolean();
  }

  private static long integer(JsonNode args, String name, long fallback, long min, long max) throws ConnectorException {
    JsonNode value = args.get(name);
    if (value == null) return fallback;
    if (!value.isIntegralNumber() || value.bigIntegerValue().compareTo(BigInteger.valueOf(min)) < 0
        || value.bigIntegerValue().compareTo(BigInteger.valueOf(max)) > 0) {
      throw ConnectorException.badInput(name + " must be an integer from " + min + " to " + max + ".");
    }
    return value.longValue();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
