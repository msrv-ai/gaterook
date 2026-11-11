package ai.msrv.gaterook.connectors.spanner;

import ai.msrv.gaterook.connector.api.ToolResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spanner's values both ways. Results ({@code google.protobuf.Value} by the result's {@code rowType}) become typed
 * cells: INT64 a number only where JavaScript keeps it exact, NUMERIC as text, FLOAT64 a number (NaN and the
 * infinities as text), BYTES and PROTO a hex preview, ARRAY an array, STRUCT an object, strings cut to the cell size.
 * Parameters ({@code {name, type, value}}) become the request's {@code params} and {@code paramTypes}.
 */
final class Values {

  static final long MAX_SAFE_INTEGER = 9_007_199_254_740_992L;
  static final int MAX_BYTES_SHOWN = 256;
  static final int MAX_DEPTH = 15;
  static final int MAX_PARAMETERS = 100;
  static final Set<String> PARAMETER_TYPES = Set.of("STRING", "INT64", "FLOAT64", "NUMERIC", "BOOL", "DATE",
      "TIMESTAMP", "BYTES", "JSON");
  private static final Pattern PARAMETER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
  private static final Pattern INTEGER = Pattern.compile("-?\\d{1,19}");
  private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?"
      + "([Zz]|[+-]\\d{2}:\\d{2})");
  /** PostgreSQL-dialect names of Spanner's type codes. */
  private static final Map<String, String> POSTGRES = Map.of("INT64", "bigint", "FLOAT64", "double precision",
      "FLOAT32", "real", "BOOL", "boolean", "STRING", "character varying", "BYTES", "bytea", "TIMESTAMP",
      "timestamp with time zone", "DATE", "date", "NUMERIC", "numeric", "JSON", "jsonb");
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private Values() {}

  // ── Results ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** The columns of a result's {@code rowType}, typed in the database's dialect. */
  static List<ToolResult.Column> columns(JsonNode rowType, boolean postgres) {
    List<ToolResult.Column> out = new ArrayList<>();
    int i = 0;
    for (JsonNode field : rowType.path("fields")) {
      String name = field.path("name").asString("");
      out.add(new ToolResult.Column(name.isEmpty() ? "_" + i : name, typeName(field.path("type"), postgres), null,
          null, null));
      i++;
    }
    return out;
  }

  /** A Spanner type as its dialect writes it: {@code ARRAY<INT64>}, {@code STRUCT<a INT64>}; {@code bigint[]}. */
  static String typeName(JsonNode type, boolean postgres) {
    String code = type.path("code").asString("");
    if ("ARRAY".equals(code)) {
      String element = typeName(type.path("arrayElementType"), postgres);
      return postgres ? element + "[]" : "ARRAY<" + element + ">";
    }
    if ("STRUCT".equals(code)) {
      List<String> fields = new ArrayList<>();
      for (JsonNode f : type.path("structType").path("fields")) {
        String name = f.path("name").asString("");
        fields.add((name.isEmpty() ? "" : name + " ") + typeName(f.path("type"), postgres));
      }
      return "STRUCT<" + String.join(", ", fields) + ">";
    }
    if (postgres) {
      String annotation = type.path("typeAnnotation").asString("");
      if ("PG_OID".equals(annotation)) {
        return "oid";
      }
      return POSTGRES.getOrDefault(code, code.toLowerCase(Locale.ROOT));
    }
    return code;
  }

  /** One row as an array of cells. */
  static ArrayNode row(List<JsonNode> values, JsonNode rowType, int maxCellChars) {
    ArrayNode out = F.arrayNode();
    JsonNode fields = rowType.path("fields");
    for (int i = 0; i < values.size(); i++) {
      out.add(value(values.get(i), fields.path(i).path("type"), maxCellChars, 0));
    }
    return out;
  }

  static JsonNode value(JsonNode v, JsonNode type, int maxCellChars, int depth) {
    if (v == null || v.isMissingNode() || v.isNull() || depth > MAX_DEPTH) {
      return F.nullNode();
    }
    String code = type.path("code").asString("");
    return switch (code) {
      case "ARRAY" -> {
        ArrayNode out = F.arrayNode();
        for (JsonNode e : v) {
          out.add(value(e, type.path("arrayElementType"), maxCellChars, depth + 1));
        }
        yield out;
      }
      case "STRUCT" -> {
        ObjectNode out = F.objectNode();
        JsonNode fields = type.path("structType").path("fields");
        for (int i = 0; i < fields.size(); i++) {
          String name = fields.get(i).path("name").asString("");
          out.set(name.isEmpty() ? "_" + i : name, value(v.path(i), fields.get(i).path("type"), maxCellChars,
              depth + 1));
        }
        yield out;
      }
      case "INT64", "ENUM" -> integer(v.isNumber() ? v.asString() : v.asString(""));
      case "FLOAT64", "FLOAT32" -> {
        if (v.isNumber()) {
          yield F.numberNode(v.asDouble());
        }
        yield F.stringNode(v.asString(""));
      }
      case "BOOL" -> F.booleanNode(v.isBoolean() ? v.asBoolean() : "true".equalsIgnoreCase(v.asString("")));
      case "BYTES", "PROTO" -> bytes(v.asString(""));
      default -> text(v.isString() ? v.asString() : v.toString(), maxCellChars);
    };
  }

  static JsonNode integer(String text) {
    try {
      BigDecimal n = new BigDecimal(text);
      if (n.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) <= 0) {
        return F.numberNode(n.longValueExact());
      }
    } catch (NumberFormatException | ArithmeticException e) {
      // Not an integer: shown as Spanner wrote it.
    }
    return F.stringNode(text);
  }

  private static JsonNode bytes(String base64) {
    byte[] b;
    try {
      b = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return F.stringNode(base64);
    }
    int shown = Math.min(b.length, MAX_BYTES_SHOWN);
    String hex = "\\x" + HexFormat.of().formatHex(b, 0, shown);
    return F.stringNode(b.length > shown ? hex + "…‹" + b.length + " bytes›" : hex);
  }

  static JsonNode text(String v, int max) {
    if (v.length() <= max) {
      return F.stringNode(v);
    }
    int end = Character.isHighSurrogate(v.charAt(max - 1)) ? max - 1 : max;
    return F.stringNode(v.substring(0, end) + "…‹" + v.length() + " characters›");
  }

  // ── Parameters ──────────────────────────────────────────────────────────────────────────────────────────────

  /** Why {@code parameters} aren't Spanner's named scalar parameters, or {@code null}. */
  static String problem(JsonNode parameters) {
    if (parameters == null || parameters.isMissingNode() || parameters.isNull()) {
      return null;
    }
    if (!parameters.isArray()) {
      return "parameters must be an array of {name, type, value}.";
    }
    if (parameters.size() > MAX_PARAMETERS) {
      return "At most " + MAX_PARAMETERS + " parameters per statement.";
    }
    for (JsonNode p : parameters) {
      String name = p.path("name").asString("");
      if (!PARAMETER.matcher(name).matches()) {
        return "A parameter needs a name: since for @since in GoogleSQL, p1 for $1 in a PostgreSQL-dialect database.";
      }
      String type = p.path("type").asString("");
      if (!PARAMETER_TYPES.contains(type)) {
        return "Parameter " + name + " has a type Spanner parameters don't take here: use one of "
            + String.join(", ", PARAMETER_TYPES.stream().sorted().toList()) + ".";
      }
      JsonNode v = p.path("value");
      if (v.isNull()) {
        continue;
      }
      String bad = switch (type) {
        case "INT64" -> v.isIntegralNumber() || (v.isString() && INTEGER.matcher(v.asString()).matches()) ? null
            : "a whole number";
        case "FLOAT64" -> v.isNumber() || (v.isString() && Set.of("NaN", "Infinity", "-Infinity")
            .contains(v.asString())) ? null : "a number";
        case "NUMERIC" -> numeric(v) ? null : "a decimal number";
        case "BOOL" -> v.isBoolean() ? null : "true or false";
        case "DATE" -> v.isString() && DATE.matcher(v.asString()).matches() ? null : "a date such as 2026-11-11";
        case "TIMESTAMP" -> v.isString() && TIMESTAMP.matcher(v.asString()).matches() ? null
            : "a time such as 2026-11-11T09:30:00Z";
        case "BYTES" -> v.isString() && base64(v.asString()) ? null : "base64 text";
        default -> v.isString() ? null : "text";
      };
      if (bad != null) {
        return "Parameter " + name + " (" + type + ") needs " + bad + ".";
      }
    }
    return null;
  }

  private static boolean numeric(JsonNode v) {
    if (v.isNumber()) {
      return true;
    }
    if (!v.isString()) {
      return false;
    }
    try {
      new BigDecimal(v.asString());
      return true;
    } catch (NumberFormatException e) {
      return "NaN".equals(v.asString());
    }
  }

  private static boolean base64(String s) {
    try {
      Base64.getDecoder().decode(s);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Sets {@code params} and {@code paramTypes} of an ExecuteSql request. In a PostgreSQL-dialect database NUMERIC and
   * JSON carry the type annotations {@code PG_NUMERIC} and {@code PG_JSONB}.
   */
  static void encode(JsonNode parameters, ObjectNode request, boolean postgres) {
    if (parameters == null || !parameters.isArray() || parameters.isEmpty()) {
      return;
    }
    ObjectNode params = request.putObject("params");
    ObjectNode types = request.putObject("paramTypes");
    for (JsonNode p : parameters) {
      String name = p.path("name").asString();
      String type = p.path("type").asString();
      JsonNode v = p.path("value");
      ObjectNode t = types.putObject(name).put("code", type);
      if (postgres && "NUMERIC".equals(type)) {
        t.put("typeAnnotation", "PG_NUMERIC");
      } else if (postgres && "JSON".equals(type)) {
        t.put("typeAnnotation", "PG_JSONB");
      }
      if (v.isNull() || v.isMissingNode()) {
        params.putNull(name);
      } else if ("BOOL".equals(type)) {
        params.put(name, v.asBoolean());
      } else if ("FLOAT64".equals(type) && v.isNumber()) {
        params.put(name, v.asDouble());
      } else {
        // INT64 and NUMERIC as decimal text, the rest as the text they are.
        params.put(name, v.isNumber() ? v.asString() : v.asString(""));
      }
    }
  }
}
