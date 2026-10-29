package ai.msrv.gaterook.connectors.sql;

import ai.msrv.gaterook.connector.api.ConnectorException;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The typed parameters of a SQL template (kit Connectors › Templates, report 10 §6.6): String ({@code maxLength} up to
 * 4,000, 256 by default, {@code pattern}), Enum, Integer ({@code minimum}/{@code maximum}), Number (decimal), Boolean,
 * Timestamp (with an offset) and Date. A default is a literal, or for times an ISO-8601 duration from now
 * ({@code -PT24H}: 24 hours ago), computed at the call. Values are bound by the driver, never pasted into the SQL;
 * a NULL is bound with its type (PostgreSQL can't infer the type of an untyped NULL parameter).
 */
final class TemplateParams {

  static final int MAX_PARAMS = 20;
  static final int MAX_STRING = 4_000;
  static final int DEFAULT_STRING = 256;
  private static final Pattern NAME = Pattern.compile("^[a-z_][a-z0-9_]{0,31}$");

  enum Type { STRING, ENUM, INTEGER, NUMBER, BOOLEAN, TIMESTAMP, DATE }

  /** One parameter as defined. */
  record Param(String name, Type type, boolean required, List<String> values, JsonNode dflt, String description,
      int maxLength, String pattern, BigDecimal minimum, BigDecimal maximum) {}

  /** A value ready to bind: {@code String}, {@code Long}, {@code BigDecimal}, {@code Boolean}, {@code OffsetDateTime},
   * {@code LocalDate}, or {@code null}. */
  record Bound(String name, Type type, Object value) {}

  private TemplateParams() {}

  // ── Definitions ─────────────────────────────────────────────────────────────────────────────────────────────

  static List<Param> parse(JsonNode params, List<String> errors) {
    List<Param> out = new ArrayList<>();
    if (params == null || params.isMissingNode() || params.isNull()) {
      return out;
    }
    if (!params.isArray()) {
      errors.add("Parameters must be a list.");
      return out;
    }
    if (params.size() > MAX_PARAMS) {
      errors.add("A template has at most " + MAX_PARAMS + " parameters; this one has " + params.size() + ".");
    }
    Set<String> names = new HashSet<>();
    for (JsonNode p : params) {
      String name = p.path("name").asString("");
      if (!NAME.matcher(name).matches()) {
        errors.add("Parameter name " + (name.isEmpty() ? "(empty)" : name) + ": use lowercase letters, digits and _.");
        continue;
      }
      if (!names.add(name)) {
        errors.add("Parameter :" + name + " is defined twice.");
        continue;
      }
      Type type;
      try {
        type = Type.valueOf(p.path("type").asString("string").toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        errors.add("Parameter :" + name + ": unknown type " + p.path("type").asString() + ".");
        continue;
      }
      List<String> values = new ArrayList<>();
      p.path("values").forEach(v -> values.add(v.asString()));
      if (type == Type.ENUM && values.isEmpty()) {
        errors.add("Parameter :" + name + " is an enum without allowed values.");
      }
      int maxLength = p.path("maxLength").asInt(DEFAULT_STRING);
      if (maxLength < 1 || maxLength > MAX_STRING) {
        errors.add("Parameter :" + name + ": maxLength is 1 to " + MAX_STRING + ".");
      }
      String pattern = p.path("pattern").isString() ? p.get("pattern").asString() : null;
      if (pattern != null) {
        try {
          Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
          errors.add("Parameter :" + name + ": the pattern isn't a regular expression.");
        }
      }
      BigDecimal minimum = p.path("minimum").isNumber() ? p.get("minimum").decimalValue() : null;
      BigDecimal maximum = p.path("maximum").isNumber() ? p.get("maximum").decimalValue() : null;
      JsonNode dflt = p.has("default") && !p.get("default").isNull() ? p.get("default") : null;
      Param param = new Param(name, type, p.path("required").asBoolean(true), List.copyOf(values), dflt,
          p.path("description").asString(null), Math.max(1, Math.min(MAX_STRING, maxLength)), pattern, minimum,
          maximum);
      if (dflt != null) {
        try {
          value(param, dflt, Clock.systemUTC());
        } catch (ConnectorException e) {
          errors.add("Parameter :" + name + ": the default " + dflt + " isn't a " + type.name().toLowerCase(Locale.ROOT)
              + (type == Type.TIMESTAMP || type == Type.DATE ? " or an ISO 8601 duration such as -PT24H" : "") + ".");
        }
      }
      out.add(param);
    }
    return out;
  }

  /**
   * The tool's input schema, as the kit's editor shows it ("What the agent sees"): one property per parameter, in
   * order; required ones without a default are required. A time is checked when it is parsed, so it needs no bounds
   * here; an argument that isn't a parameter is never bound.
   */
  static ObjectNode inputSchema(List<Param> params) {
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    ArrayNode required = JsonNodeFactory.instance.arrayNode();
    for (Param p : params) {
      ObjectNode prop = properties.putObject(p.name());
      switch (p.type()) {
        case STRING -> {
          prop.put("type", "string").put("maxLength", p.maxLength());
          if (p.pattern() != null) {
            prop.put("pattern", p.pattern());
          }
        }
        case ENUM -> {
          prop.put("type", "string");
          ArrayNode e = prop.putArray("enum");
          p.values().forEach(e::add);
        }
        case INTEGER -> {
          prop.put("type", "integer");
          bounds(prop, p);
        }
        case NUMBER -> {
          prop.put("type", "number");
          bounds(prop, p);
        }
        case BOOLEAN -> prop.put("type", "boolean");
        case TIMESTAMP -> prop.put("type", "string").put("format", "date-time");
        case DATE -> prop.put("type", "string").put("format", "date");
      }
      String description = p.description() == null ? "" : p.description().strip();
      String dflt = defaultText(p);
      if (dflt != null) {
        description = description.isEmpty() ? "Default: " + dflt : description
            + (description.endsWith(".") ? " " : ". ") + "Default: " + dflt;
      }
      if (!description.isEmpty()) {
        prop.put("description", description);
      }
      if (p.dflt() != null && !duration(p)) {
        prop.set("default", p.dflt().deepCopy());
      }
      if (p.required() && p.dflt() == null) {
        required.add(p.name());
      }
    }
    if (!required.isEmpty()) {
      schema.set("required", required);
    }
    return schema;
  }

  /** {@code 24 hours ago} for {@code -PT24H}; the literal otherwise; {@code null} without a default. */
  private static String defaultText(Param p) {
    if (p.dflt() == null) {
      return null;
    }
    if (duration(p)) {
      String text = p.dflt().asString();
      try {
        Duration d = Duration.parse(text);
        return human(d.abs()) + (d.isNegative() ? " ago" : " from now");
      } catch (DateTimeParseException e) {
        return text + " from now";
      }
    }
    return p.dflt().isString() ? p.dflt().asString() : p.dflt().toString();
  }

  private static String human(Duration d) {
    long hours = d.toHours();
    if (d.toMinutesPart() == 0 && d.toSecondsPart() == 0 && hours > 0) {
      // -PT24H reads "24 hours ago", as the kit shows it; whole days from two on.
      return hours % 24 == 0 && hours >= 48 ? (hours / 24) + " days" : hours + (hours == 1 ? " hour" : " hours");
    }
    long minutes = d.toMinutes();
    return minutes + (minutes == 1 ? " minute" : " minutes");
  }

  private static boolean duration(Param p) {
    return (p.type() == Type.TIMESTAMP || p.type() == Type.DATE) && p.dflt() != null && p.dflt().isString()
        && (p.dflt().asString().startsWith("P") || p.dflt().asString().startsWith("-P"));
  }

  private static void bounds(ObjectNode prop, Param p) {
    if (p.minimum() != null) {
      prop.put("minimum", p.minimum());
    }
    if (p.maximum() != null) {
      prop.put("maximum", p.maximum());
    }
  }

  static ArrayNode toJson(List<Param> params) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    for (Param p : params) {
      ObjectNode o = out.addObject().put("name", p.name()).put("type", p.type().name().toLowerCase(Locale.ROOT))
          .put("required", p.required()).put("maxLength", p.maxLength());
      if (!p.values().isEmpty()) {
        ArrayNode v = o.putArray("values");
        p.values().forEach(v::add);
      }
      if (p.dflt() != null) {
        o.set("default", p.dflt().deepCopy());
      }
      if (p.pattern() != null) {
        o.put("pattern", p.pattern());
      }
      if (p.minimum() != null) {
        o.put("minimum", p.minimum());
      }
      if (p.maximum() != null) {
        o.put("maximum", p.maximum());
      }
    }
    return out;
  }

  // ── Values ──────────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * The values of a call in placeholder order: the agent's argument, else the default (a duration from now), else
   * NULL for an optional parameter.
   */
  static List<Bound> resolve(List<Param> params, List<String> order, JsonNode arguments, Clock clock)
      throws ConnectorException {
    Map<String, Param> byName = new java.util.LinkedHashMap<>();
    params.forEach(p -> byName.put(p.name(), p));
    Map<String, Bound> resolved = new java.util.LinkedHashMap<>();
    for (Param p : params) {
      JsonNode given = arguments == null ? null : arguments.get(p.name());
      Object value;
      if (given != null && !given.isNull()) {
        value = value(p, given, clock);
      } else if (p.dflt() != null) {
        value = value(p, p.dflt(), clock);
      } else if (p.required()) {
        throw ConnectorException.badInput(p.name() + " is required.");
      } else {
        value = null;
      }
      resolved.put(p.name(), new Bound(p.name(), p.type(), value));
    }
    List<Bound> out = new ArrayList<>();
    for (String name : order) {
      Bound b = resolved.get(name);
      if (b == null) {
        throw ConnectorException.badInput(":" + name + " isn't a parameter of this template.");
      }
      out.add(b);
    }
    return out;
  }

  /** The parameters by name, each once, as resolved: what the approver sees and what the approval stores. */
  static List<Bound> distinct(List<Bound> values) {
    Map<String, Bound> out = new java.util.LinkedHashMap<>();
    values.forEach(b -> out.putIfAbsent(b.name(), b));
    return List.copyOf(out.values());
  }

  static Object value(Param p, JsonNode v, Clock clock) throws ConnectorException {
    try {
      return switch (p.type()) {
        case STRING -> {
          String s = text(p, v);
          if (s.length() > p.maxLength()) {
            throw ConnectorException.badInput(p.name() + " is longer than " + p.maxLength() + " characters.");
          }
          if (p.pattern() != null && !Pattern.compile(p.pattern()).matcher(s).matches()) {
            throw ConnectorException.badInput(p.name() + " doesn't match " + p.pattern() + ".");
          }
          yield s;
        }
        case ENUM -> {
          String s = text(p, v);
          if (!p.values().contains(s)) {
            throw ConnectorException.badInput(p.name() + " must be one of " + String.join(", ", p.values()) + ".");
          }
          yield s;
        }
        case INTEGER -> {
          if (!v.isIntegralNumber() && !(v.isString() && v.asString().matches("-?\\d{1,18}"))) {
            throw ConnectorException.badInput(p.name() + " must be an integer.");
          }
          long n = v.isString() ? Long.parseLong(v.asString()) : v.asLong();
          checkBounds(p, BigDecimal.valueOf(n));
          yield n;
        }
        case NUMBER -> {
          BigDecimal n = v.isNumber() ? v.decimalValue() : new BigDecimal(text(p, v));
          checkBounds(p, n);
          yield n;
        }
        case BOOLEAN -> {
          if (!v.isBoolean()) {
            throw ConnectorException.badInput(p.name() + " must be true or false.");
          }
          yield v.asBoolean();
        }
        case TIMESTAMP -> {
          String s = text(p, v);
          if (s.startsWith("P") || s.startsWith("-P")) {
            yield OffsetDateTime.ofInstant(clock.instant().plus(Duration.parse(s)), ZoneOffset.UTC);
          }
          yield OffsetDateTime.parse(s);
        }
        case DATE -> {
          String s = text(p, v);
          if (s.startsWith("P") || s.startsWith("-P")) {
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            yield s.contains("T") ? LocalDate.ofInstant(clock.instant().plus(Duration.parse(s)), ZoneOffset.UTC)
                : today.plus(Period.parse(s));
          }
          yield LocalDate.parse(s);
        }
      };
    } catch (DateTimeParseException | NumberFormatException | ArithmeticException e) {
      throw ConnectorException.badInput(p.name() + " isn't a valid " + p.type().name().toLowerCase(Locale.ROOT)
          + (p.type() == Type.TIMESTAMP ? ": use ISO 8601 with an offset, such as 2026-10-22T14:00:00Z" : "") + ".");
    }
  }

  private static String text(Param p, JsonNode v) throws ConnectorException {
    if (!v.isString()) {
      throw ConnectorException.badInput(p.name() + " must be a string.");
    }
    return v.asString();
  }

  private static void checkBounds(Param p, BigDecimal n) throws ConnectorException {
    if (p.minimum() != null && n.compareTo(p.minimum()) < 0) {
      throw ConnectorException.badInput(p.name() + " is at least " + p.minimum().toPlainString() + ".");
    }
    if (p.maximum() != null && n.compareTo(p.maximum()) > 0) {
      throw ConnectorException.badInput(p.name() + " is at most " + p.maximum().toPlainString() + ".");
    }
  }

  /** Binds every value with its JDBC type; NULL with the type too. */
  static void bind(PreparedStatement ps, List<Bound> values, boolean mysql) throws SQLException {
    bind(ps, values, mysql ? new MySqlDialect() : new PostgresDialect());
  }

  static void bind(PreparedStatement ps, List<Bound> values, SqlDialect dialect) throws SQLException {
    int i = 1;
    for (Bound b : values) {
      if (b.value() == null) {
        int type = dialect.sqlServer() && b.type() == Type.TIMESTAMP
            ? microsoft.sql.Types.DATETIMEOFFSET : sqlType(b.type(), dialect.mysql());
        ps.setNull(i++, type);
        continue;
      }
      switch (b.value()) {
        case String s -> ps.setString(i, s);
        case Long n -> ps.setLong(i, n);
        case BigDecimal n -> ps.setBigDecimal(i, n);
        case Boolean v -> ps.setBoolean(i, v);
        case OffsetDateTime t -> ps.setObject(i, t);
        case LocalDate d -> ps.setObject(i, d);
        default -> throw new SQLException("Unsupported template value " + b.value().getClass().getSimpleName());
      }
      i++;
    }
  }

  private static int sqlType(Type type, boolean mysql) {
    return switch (type) {
      case STRING, ENUM -> Types.VARCHAR;
      case INTEGER -> Types.BIGINT;
      case NUMBER -> Types.NUMERIC;
      case BOOLEAN -> Types.BOOLEAN;
      case TIMESTAMP -> mysql ? Types.TIMESTAMP : Types.TIMESTAMP_WITH_TIMEZONE;
      case DATE -> Types.DATE;
    };
  }

  /** {@code :status = 'cancelled' (enum), :id = 48213 (integer)}: what the approver reads. */
  static String describe(List<Bound> values) {
    List<String> out = new ArrayList<>();
    for (Bound b : distinct(values)) {
      String v = switch (b.value()) {
        case null -> "NULL";
        case String s -> "'" + s.replace("'", "''") + "'";
        case BigDecimal n -> n.toPlainString();
        default -> String.valueOf(b.value());
      };
      out.add(":" + b.name() + " = " + v + " (" + b.type().name().toLowerCase(Locale.ROOT) + ")");
    }
    return out.isEmpty() ? "none" : String.join(", ", out);
  }

  /** The resolved values as JSON for the approval's payload; {@link #fromJson} reads them back with their types. */
  static ObjectNode valuesJson(List<Bound> values) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    for (Bound b : distinct(values)) {
      switch (b.value()) {
        case null -> out.putNull(b.name());
        case String s -> out.put(b.name(), s);
        case Long n -> out.put(b.name(), n);
        case BigDecimal n -> out.put(b.name(), n.toPlainString());
        case Boolean v -> out.put(b.name(), v);
        default -> out.put(b.name(), b.value().toString());
      }
    }
    return out;
  }

  /** Values of an approved payload, in placeholder order, typed again from the template's parameters. */
  static List<Bound> fromJson(List<Param> params, List<String> order, JsonNode values) throws ConnectorException {
    Map<String, Param> byName = new java.util.LinkedHashMap<>();
    params.forEach(p -> byName.put(p.name(), p));
    List<Bound> out = new ArrayList<>();
    for (String name : order) {
      Param p = byName.get(name);
      if (p == null) {
        throw ConnectorException.badInput(":" + name + " isn't a parameter of this template.");
      }
      JsonNode v = values == null ? null : values.get(name);
      if (v == null || (v.isNull() && (p.required() || p.dflt() != null))) {
        // Every resolved value is stored, a NULL one too, and only an optional parameter without a default resolves
        // to NULL: otherwise these values aren't the ones a person approved, and nothing runs with a guessed NULL.
        throw ConnectorException.badInput("The approved values of this template have no value for :" + name + ".");
      }
      if (v.isNull()) {
        out.add(new Bound(name, p.type(), null));
        continue;
      }
      // Stored canonically: numbers as text, times in ISO 8601; never a duration (it was computed at request time).
      JsonNode typed = p.type() == Type.INTEGER && v.isString() && v.asString().matches("-?\\d{1,18}")
          ? JsonNodeFactory.instance.numberNode(Long.parseLong(v.asString())) : v;
      out.add(new Bound(name, p.type(), value(p, typed, Clock.systemUTC())));
    }
    return out;
  }
}
