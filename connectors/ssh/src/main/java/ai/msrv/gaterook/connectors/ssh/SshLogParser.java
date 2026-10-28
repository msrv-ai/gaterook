package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Parses one already sanitized and masked physical log line; never infers a missing date or local time zone. */
final class SshLogParser {
  private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final List<String> TIMESTAMPS = List.of("@timestamp", "timestamp", "time", "ts");
  private static final List<String> LEVELS = List.of("level", "severity", "log.level");
  private static final Pattern PREFIX = Pattern.compile(
      "^\\s*\\[?(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?"
          + "(?: ?(?:[zZ]|[+-]\\d{2}:?\\d{2}))?)(?=$|[\\]\\s])");
  private static final Pattern LEVEL = Pattern.compile(
      "(?i)^(TRACE|DEBUG|INFO|NOTICE|WARN(?:ING)?|ERROR|ERR|SEVERE|FATAL|CRITICAL|CRIT|ALERT|EMERGENCY|EMERG)(?=$|[\\s\\]:-])");
  private static final BigInteger BILLION = BigInteger.valueOf(1_000_000_000);

  enum TimestampStatus { PARSED, MISSING, INVALID }

  record Parsed(Instant timestamp, String level, String text, TimestampStatus timestampStatus, boolean malformed) {}

  private final String format;
  private final ZoneId zone;
  private final String timestampField;
  private final String levelField;
  private final DateTimeFormatter custom;
  private final boolean seconds;

  private SshLogParser(JsonNode config) throws ConnectorException {
    format = setting(config, "format", "auto");
    if (!List.of("auto", "text", "jsonl").contains(format)) {
      throw ConnectorException.badInput("Log format must be auto, text or jsonl.");
    }
    try {
      zone = ZoneId.of(setting(config, "timezone", "UTC"));
    } catch (DateTimeException e) {
      throw ConnectorException.badInput("Choose a valid log time zone, such as UTC or Europe/Madrid.");
    }
    timestampField = setting(config, "timestampField", null);
    levelField = setting(config, "levelField", null);
    String unit = setting(config, "timestampUnit", "milliseconds");
    if (!unit.equals("seconds") && !unit.equals("milliseconds")) {
      throw ConnectorException.badInput("Numeric JSON timestamps use seconds or milliseconds.");
    }
    seconds = unit.equals("seconds");
    String pattern = setting(config, "timestampPattern", null);
    try {
      custom = pattern == null ? null : new DateTimeFormatterBuilder().appendPattern(pattern)
          .parseDefaulting(ChronoField.ERA, 1).toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    } catch (IllegalArgumentException e) {
      throw ConnectorException.badInput("The timestamp pattern is not a valid DateTimeFormatter pattern.");
    }
  }

  static SshLogParser of(SshLogSettings settings) throws ConnectorException {
    return new SshLogParser(settings.logsConfig());
  }

  static SshLogParser of(ConnectionConfig config) throws ConnectorException {
    return new SshLogParser(config.config().path("logs"));
  }

  static SshLogParser of(JsonNode logsConfig) throws ConnectorException {
    return new SshLogParser(logsConfig);
  }

  private static String setting(JsonNode config, String name, String fallback) throws ConnectorException {
    JsonNode value = config.path(name);
    if (value.isMissingNode() || value.isNull()) return fallback;
    int limit = name.equals("timezone") ? 100 : 256;
    if (!value.isString() || value.asString().length() > limit || value.asString().codePoints().anyMatch(Character::isISOControl)) {
      throw ConnectorException.badInput("Invalid log parsing setting: " + name + ".");
    }
    String text = value.asString().strip();
    return text.isEmpty() ? fallback : text;
  }

  Parsed parse(String maskedText) {
    String line = maskedText == null ? "" : maskedText;
    if (format.equals("jsonl") || (format.equals("auto") && line.stripLeading().startsWith("{"))) {
      return json(line);
    }
    return text(line);
  }

  /** Field-aware JSON masking precedes timestamp, severity and content filters; offsets still refer to raw bytes. */
  String mask(String rawText, ExecContext ctx) {
    String line = rawText == null ? "" : rawText;
    if (format.equals("jsonl") || (format.equals("auto") && line.stripLeading().startsWith("{"))) {
      JsonNode root = null;
      try {
        root = JSON.readTree(line);
      } catch (RuntimeException ignored) {
        // A malformed JSON line remains visible as text and is counted when its timestamp cannot be filtered.
      }
      if (root != null && root.isObject()) {
        return OutputSanitizer.sanitize(ctx.masked(root).toString());
      }
    }
    return OutputSanitizer.sanitize(ctx.masked(JsonNodeFactory.instance.textNode(line)).asString());
  }

  private Parsed json(String line) {
    try {
      JsonNode root = JSON.readTree(line);
      if (root == null || !root.isObject()) {
        return new Parsed(null, null, line, TimestampStatus.INVALID, true);
      }
      JsonNode stamp = timestampField == null ? first(root, TIMESTAMPS) : field(root, timestampField);
      JsonNode severity = levelField == null ? first(root, LEVELS) : field(root, levelField);
      String level = severity != null && severity.isString() ? normalizeLevel(severity.asString()) : null;
      if (stamp == null || stamp.isNull()) {
        return new Parsed(null, level, line, TimestampStatus.MISSING, false);
      }
      Instant time = stamp.isNumber() ? numeric(stamp.decimalValue())
          : stamp.isString() ? timestamp(stamp.asString(), false) : null;
      return new Parsed(time, level, line, time == null ? TimestampStatus.INVALID : TimestampStatus.PARSED, false);
    } catch (RuntimeException e) {
      return new Parsed(null, null, line, TimestampStatus.INVALID, true);
    }
  }

  private Parsed text(String line) {
    String prefix = line.substring(0, Math.min(line.length(), 512));
    String level = headerLevel(prefix);
    Instant stamp = timestamp(prefix, true);
    boolean looksDated = custom != null || prefix.stripLeading().matches("\\[?\\d{4}-.*");
    return new Parsed(stamp, level, line, stamp != null ? TimestampStatus.PARSED
        : looksDated ? TimestampStatus.INVALID : TimestampStatus.MISSING, false);
  }

  /** Severity belongs to the header; a word such as "error" inside an unlevelled message is not a log level. */
  private String headerLevel(String prefix) {
    String header = prefix.stripLeading();
    if (custom != null) {
      try {
        ParsePosition position = new ParsePosition(0);
        custom.parse(header, position);
        if (position.getErrorIndex() < 0 && position.getIndex() > 0) header = header.substring(position.getIndex());
      } catch (DateTimeException ignored) {
        // An unrecognized date leaves no inferred level in its message.
      }
    } else {
      var time = PREFIX.matcher(header);
      if (time.find()) {
        boolean bracketed = header.startsWith("[");
        header = header.substring(time.end());
        if (bracketed && header.startsWith("]")) header = header.substring(1);
      }
    }
    header = header.stripLeading();
    for (int i = 0; i < 8; i++) {
      var level = LEVEL.matcher(header);
      if (level.find()) return normalizeLevel(level.group(1));
      if (!header.startsWith("[")) return null;
      int close = header.indexOf(']');
      if (close < 0) return null;
      String value = normalizeLevel(header.substring(1, close));
      if (value != null) return value;
      header = header.substring(close + 1).stripLeading();
    }
    return null;
  }

  private Instant timestamp(String value, boolean prefix) {
    if (value.length() > 512 && !prefix) return null;
    String text = value.stripLeading();
    try {
      if (custom != null) {
        ParsePosition position = new ParsePosition(0);
        TemporalAccessor parsed = custom.parse(text, position);
        if (position.getErrorIndex() >= 0 || position.getIndex() == 0
            || (!prefix && position.getIndex() != text.stripTrailing().length())) return null;
        LocalDateTime local = LocalDateTime.from(parsed);
        ZoneOffset offset = parsed.query(TemporalQueries.offset());
        if (offset != null) return local.toInstant(offset);
        ZoneId parsedZone = parsed.query(TemporalQueries.zone());
        return local(local, parsedZone == null ? zone : parsedZone);
      }
      var matcher = PREFIX.matcher(text);
      if (!matcher.find()) return null;
      if (!prefix && matcher.end() != text.stripTrailing().length()) return null;
      String token = matcher.group(1).replace(',', '.');
      token = token.substring(0, 10) + "T" + token.substring(11);
      token = token.replace(" ", "").replace('z', 'Z');
      if (token.matches(".*[+-]\\d{4}$")) {
        token = token.substring(0, token.length() - 2) + ":" + token.substring(token.length() - 2);
      }
      TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parse(token);
      LocalDateTime local = LocalDateTime.from(parsed);
      ZoneOffset offset = parsed.query(TemporalQueries.offset());
      return offset == null ? local(local, zone) : local.toInstant(offset);
    } catch (DateTimeException | IndexOutOfBoundsException e) {
      return null;
    }
  }

  /** DST overlaps and gaps have no unique instant; the log must include an offset to disambiguate them. */
  private static Instant local(LocalDateTime time, ZoneId zone) {
    var offsets = zone.getRules().getValidOffsets(time);
    return offsets.size() == 1 ? time.toInstant(offsets.getFirst()) : null;
  }

  private Instant numeric(BigDecimal value) {
    try {
      if ((long) value.precision() - value.scale() > 20 || Math.abs((long) value.scale()) > 30) return null;
      BigInteger nanos = value.movePointRight(seconds ? 9 : 6).toBigIntegerExact();
      BigInteger[] parts = nanos.divideAndRemainder(BILLION);
      return Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValueExact());
    } catch (ArithmeticException | DateTimeException e) {
      return null;
    }
  }

  private static JsonNode first(JsonNode root, List<String> names) {
    for (String name : names) {
      JsonNode value = field(root, name);
      if (value != null && !value.isNull()) return value;
    }
    return null;
  }

  /** Exact JSON keys take precedence over dot traversal; a leading slash explicitly selects JSON Pointer. */
  private static JsonNode field(JsonNode root, String name) {
    if (name.startsWith("/")) {
      JsonNode value = root.at(name);
      return value.isMissingNode() ? null : value;
    }
    if (root.has(name)) return root.get(name);
    JsonNode value = root;
    for (String part : name.split("\\.", -1)) {
      if (!value.isObject()) return null;
      value = value.get(part);
      if (value == null) return null;
    }
    return value;
  }

  static String normalizeLevel(String value) {
    if (value == null) return null;
    return switch (value.strip().toUpperCase(Locale.ROOT)) {
      case "TRACE", "DEBUG", "INFO", "NOTICE" -> value.strip().toUpperCase(Locale.ROOT);
      case "WARN", "WARNING" -> "WARN";
      case "ERROR", "ERR", "SEVERE" -> "ERROR";
      case "FATAL", "CRITICAL", "CRIT", "ALERT", "EMERGENCY", "EMERG" -> "FATAL";
      default -> null;
    };
  }
}
