package ai.msrv.gaterook.connectors.aws;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The parameters of an AWS API call ({@code aws_api_read}, {@code aws_api_change}), checked against the operation's
 * input shape and written in one canonical form, which is what the approval shows and what runs: members in model order
 * under their model names (a member spelled in another case is renamed), timestamps as ISO-8601 instants, blobs as
 * Base64, idempotency tokens filled from the call's id. Refused without any request:
 *
 * <ul>
 *   <li>a member the shape doesn't have, or a value of another JSON type than the member's;</li>
 *   <li>a value for a member the model marks {@code @sensitive} (passwords, secret strings, private keys, environment
 *       values): the AWS API tools never send secrets, so an agent can't set one it would then know;</li>
 *   <li>a missing required top-level member, a union with other than one member, deep or large input;</li>
 *   <li>text with invisible characters (control, bidi, zero-width) other than tab and line breaks.</li>
 * </ul>
 * Every ARN found in a string is recorded, so the account pin and the resource masks can be checked; so is the ARN of
 * every SQS queue URL ({@code https://sqs.<region>.amazonaws.com/<account>/<queue>}), which names a queue as well.
 */
final class ApiParams {

  static final int MAX_DEPTH = 32;
  static final int MAX_NODES = 20_000;
  static final int MAX_BYTES = 256 * 1024;
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  /** An SQS queue URL: the current form, the legacy regional one and the legacy us-east-1 one. */
  private static final java.util.regex.Pattern QUEUE_URL = java.util.regex.Pattern.compile(
      "^https://(?:sqs\\.([a-z0-9-]+)\\.|([a-z0-9-]+)\\.queue\\.|queue\\.)amazonaws\\.com(\\.cn)?/(\\d{12})/"
      + "([A-Za-z0-9_-]{1,80}(?:\\.fifo)?)/?$");

  /** The canonical input and the ARNs found in it. */
  record Checked(ObjectNode parameters, List<String> arns) {}

  private final ApiModel model;
  private final List<String> arns = new ArrayList<>();
  private int nodes;

  private ApiParams(ApiModel model) {
    this.model = model;
  }

  /**
   * Checks {@code parameters} ({@code null} or missing: none) against {@code op}'s input.
   *
   * @param callId fills idempotency tokens the agent left out, the same for every check of one call
   */
  static Checked check(ApiModel model, ApiModel.Operation op, JsonNode parameters, String callId) throws Refusal {
    if (parameters != null && !parameters.isMissingNode() && !parameters.isNull() && !parameters.isObject()) {
      throw new Refusal("bad_input", "parameters is an object of the operation's input members.", null);
    }
    ObjectNode given = parameters instanceof ObjectNode o ? o : F.objectNode();
    if (given.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new Refusal("bad_input", "parameters is larger than " + (MAX_BYTES / 1024) + " KB.", null);
    }
    if (op.input() == null) {
      if (!given.isEmpty()) {
        throw new Refusal("bad_input", op.name() + " takes no parameters.", null);
      }
      return new Checked(F.objectNode(), List.of());
    }
    ApiParams p = new ApiParams(model);
    JsonNode input = model.shape(op.input());
    ObjectNode out = p.structure(input, given, "parameters", 0);
    for (Map.Entry<String, JsonNode> m : ApiModel.members(input)) {
      JsonNode member = m.getValue();
      if (member.path("token").asInt(0) == 1 && !out.has(m.getKey())) {
        out.put(m.getKey(), token(callId));
      }
      if (member.path("req").asInt(0) == 1 && !out.has(m.getKey())) {
        throw new Refusal("bad_input", op.name() + " needs parameters." + m.getKey() + ".", null);
      }
    }
    return new Checked(reorder(input, out), List.copyOf(p.arns));
  }

  /** The ARN of the queue an SQS queue URL names, or {@code null} for anything else. */
  static String queueArn(String url) {
    java.util.regex.Matcher m = QUEUE_URL.matcher(url);
    if (!m.matches()) {
      return null;
    }
    String region = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : "us-east-1";
    String partition = m.group(3) != null ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
    return "arn:" + partition + ":sqs:" + region + ":" + m.group(4) + ":" + m.group(5);
  }

  /** The idempotency token of a call: random-looking, but the same for every check of the call. */
  static String token(String callId) {
    return UUID.nameUUIDFromBytes(("gaterook-aws-call:" + (callId == null ? "" : callId))
        .getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** The members of {@code out} in the order the shape lists them. */
  private static ObjectNode reorder(JsonNode shape, ObjectNode out) {
    ObjectNode ordered = F.objectNode();
    for (Map.Entry<String, JsonNode> m : ApiModel.members(shape)) {
      if (out.has(m.getKey())) {
        ordered.set(m.getKey(), out.get(m.getKey()));
      }
    }
    return ordered;
  }

  private ObjectNode structure(JsonNode shape, JsonNode value, String path, int depth) throws Refusal {
    if (!value.isObject()) {
      throw new Refusal("bad_input", path + " is an object.", null);
    }
    boolean union = ApiModel.type(shape).equals("union");
    ObjectNode out = F.objectNode();
    for (Map.Entry<String, JsonNode> given : value.properties()) {
      String name = memberName(shape, given.getKey(), path);
      JsonNode member = shape.path("m").get(name);
      if (given.getValue().isNull()) {
        continue;
      }
      if (out.has(name)) {
        throw new Refusal("bad_input", path + " names " + name + " twice.", null);
      }
      String at = path + "." + name;
      if (model.sensitive(member)) {
        throw new Refusal("sensitive_input", at + " is a secret value (the model marks it sensitive). GateRook never "
            + "sends secrets: set it in the AWS console or with your own credentials.", null);
      }
      out.set(name, value(model.shape(member.path("s").asString()), given.getValue(), at, depth + 1));
    }
    if (union && out.size() != 1) {
      throw new Refusal("bad_input", path + " is a union: give exactly one of " + names(shape) + ".", null);
    }
    return union ? out : reorder(shape, out);
  }

  /** The member {@code given} names: as spelled, else the only one spelled so in another case. */
  private static String memberName(JsonNode shape, String given, String path) throws Refusal {
    JsonNode members = shape.path("m");
    if (members.has(given)) {
      return given;
    }
    String found = null;
    for (String name : members.propertyNames()) {
      if (name.equalsIgnoreCase(given)) {
        if (found != null) {
          throw new Refusal("bad_input", path + "." + given + " is ambiguous: " + found + " or " + name + ".", null);
        }
        found = name;
      }
    }
    if (found == null) {
      throw new Refusal("bad_input", path + " has no member " + Requests.abbreviate(given) + ". Its members: "
          + names(shape) + ".", null);
    }
    return found;
  }

  private static String names(JsonNode shape) {
    List<String> names = new ArrayList<>();
    shape.path("m").propertyNames().forEach(names::add);
    if (names.size() > 40) {
      return String.join(", ", names.subList(0, 40)) + " and " + (names.size() - 40) + " more";
    }
    return names.isEmpty() ? "none" : String.join(", ", names);
  }

  private JsonNode value(JsonNode shape, JsonNode value, String path, int depth) throws Refusal {
    if (depth > MAX_DEPTH) {
      throw new Refusal("bad_input", path + " is nested deeper than " + MAX_DEPTH + " levels.", null);
    }
    if (++nodes > MAX_NODES) {
      throw new Refusal("bad_input", "parameters has more than " + MAX_NODES + " values.", null);
    }
    if (shape.path("streaming").asInt(0) == 1) {
      throw new Refusal("unsupported", path + " is a stream, which the AWS API tools don't send.", null);
    }
    String type = ApiModel.type(shape);
    switch (type) {
      case "structure", "union" -> {
        return structure(shape, value, path, depth);
      }
      case "list" -> {
        if (!value.isArray()) {
          throw new Refusal("bad_input", path + " is a list (a JSON array).", null);
        }
        JsonNode member = shape.path("member");
        if (model.sensitive(member)) {
          throw new Refusal("sensitive_input", path + " holds secret values (the model marks them sensitive). "
              + "they never send secrets.", null);
        }
        JsonNode target = model.shape(member.path("s").asString());
        ArrayNode out = F.arrayNode();
        int i = 0;
        for (JsonNode item : value) {
          if (item.isNull()) {
            throw new Refusal("bad_input", path + "[" + i + "] is null.", null);
          }
          out.add(value(target, item, path + "[" + i + "]", depth + 1));
          i++;
        }
        return out;
      }
      case "map" -> {
        if (!value.isObject()) {
          throw new Refusal("bad_input", path + " is a map (a JSON object).", null);
        }
        if (model.sensitive(shape.path("key")) || model.sensitive(shape.path("value"))) {
          throw new Refusal("sensitive_input", path + " holds secret values (the model marks them sensitive). "
              + "they never send secrets.", null);
        }
        JsonNode target = model.shape(shape.path("value").path("s").asString());
        ObjectNode out = F.objectNode();
        for (Map.Entry<String, JsonNode> e : value.properties()) {
          printable(e.getKey(), path + " key");
          if (e.getValue().isNull()) {
            continue;
          }
          out.set(e.getKey(), value(target, e.getValue(), path + "." + e.getKey(), depth + 1));
        }
        return out;
      }
      case "string" -> {
        if (!value.isString()) {
          throw new Refusal("bad_input", path + " is a string.", null);
        }
        String s = value.asString();
        printable(s, path);
        if (s.startsWith("arn:")) {
          arns.add(s);
        } else if (s.startsWith("https://")) {
          String queue = queueArn(s);
          if (queue != null) {
            arns.add(queue);
          }
        }
        return F.stringNode(s);
      }
      case "boolean" -> {
        if (!value.isBoolean()) {
          throw new Refusal("bad_input", path + " is true or false.", null);
        }
        return value;
      }
      case "byte", "short", "integer", "long", "bigInteger" -> {
        if (!value.isIntegralNumber() && !(value.isNumber() && isWhole(value))) {
          throw new Refusal("bad_input", path + " is a whole number.", null);
        }
        BigInteger v = value.isIntegralNumber() ? value.bigIntegerValue() : value.decimalValue().toBigInteger();
        if (!type.equals("bigInteger") && v.bitLength() > 63) {
          throw new Refusal("bad_input", path + " is out of range.", null);
        }
        return type.equals("bigInteger") ? F.numberNode(v) : F.numberNode(v.longValue());
      }
      case "float", "double", "bigDecimal" -> {
        if (!value.isNumber()) {
          throw new Refusal("bad_input", path + " is a number.", null);
        }
        return type.equals("bigDecimal") ? F.numberNode(value.decimalValue()) : F.numberNode(value.doubleValue());
      }
      case "timestamp" -> {
        return F.stringNode(timestamp(value, path).toString());
      }
      case "blob" -> {
        if (!value.isString()) {
          throw new Refusal("bad_input", path + " is Base64 text.", null);
        }
        try {
          Base64.getDecoder().decode(value.asString());
        } catch (IllegalArgumentException e) {
          throw new Refusal("bad_input", path + " isn't valid Base64.", null);
        }
        return F.stringNode(value.asString());
      }
      case "document" -> {
        return value.deepCopy();
      }
      default -> throw new Refusal("unsupported", path + " has the type " + type + ", which the AWS API tools don't "
          + "send.", null);
    }
  }

  private static boolean isWhole(JsonNode n) {
    try {
      return n.decimalValue().stripTrailingZeros().scale() <= 0;
    } catch (ArithmeticException e) {
      return false;
    }
  }

  /** An ISO-8601 instant or offset date-time, or epoch seconds. */
  static Instant timestamp(JsonNode value, String path) throws Refusal {
    if (value.isNumber()) {
      BigDecimal seconds = value.decimalValue();
      return Instant.ofEpochSecond(seconds.longValue(), seconds.remainder(BigDecimal.ONE).movePointRight(9)
          .intValue());
    }
    if (value.isString()) {
      String s = value.asString().strip();
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException e) {
        try {
          return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e2) {
          // Fall through to the refusal.
        }
      }
    }
    throw new Refusal("bad_input", path + " is a time: ISO-8601 such as 2026-11-11T09:00:00Z, or epoch seconds.",
        null);
  }

  /** Refuses control, bidi and zero-width characters other than tab and line breaks: they hide what is asked. */
  static void printable(String value, String field) throws Refusal {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\t' || c == '\n' || c == '\r') {
        continue;
      }
      if (c < 0x20 || c == 0x7f || (c >= 0x200b && c <= 0x200f) || (c >= 0x202a && c <= 0x202e)
          || (c >= 0x2066 && c <= 0x2069) || c == 0xfeff) {
        throw new Refusal("bad_input", String.format(Locale.ROOT, "%s has the invisible character U+%04X; type it "
            + "again.", field, (int) c), null);
      }
    }
  }
}
